package com.hereliesaz.graffitixr.feature.ar.computervision

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Engine responsible for generating and refining target masks using OpenCV.
 */
@Singleton
class TargetEvolutionEngine @Inject constructor() {

    /**
     * Generates an initial mask guess for the image.
     */
    suspend fun initialGuess(image: Bitmap): Bitmap = withContext(Dispatchers.IO) {
        // Simple initial guess: center 50% of the image
        val maskMat = Mat.zeros(image.height, image.width, CvType.CV_8UC1)
        val rect = Rect(image.width / 4, image.height / 4, image.width / 2, image.height / 2)
        Imgproc.rectangle(maskMat, rect, Scalar(255.0), -1)

        val resultBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(maskMat, resultBitmap)
        maskMat.release()
        resultBitmap
    }

    /**
     * Refines an existing mask based on a touch point using the FloodFill algorithm.
     *
     * @param image The original source image (RGB/RGBA).
     * @param currentMask The current binary mask (Black/White).
     * @param touchPoint The coordinate where the user touched.
     * @param isAdding Whether to add to the mask or subtract from it.
     * @return A new refined binary mask Bitmap.
     */
    suspend fun refineMask(
        image: Bitmap,
        currentMask: Bitmap,
        touchPoint: Offset,
        isAdding: Boolean
    ): Bitmap = withContext(Dispatchers.IO) {
        val srcMat = Mat()
        val maskMat = Mat()

        Utils.bitmapToMat(image, srcMat)
        
        // OpenCV's floodFill supports 1 or 3 channels. 
        // Utils.bitmapToMat usually results in 4 channels (RGBA).
        if (srcMat.channels() == 4) {
            Imgproc.cvtColor(srcMat, srcMat, Imgproc.COLOR_RGBA2RGB)
        } else if (srcMat.channels() != 1 && srcMat.channels() != 3) {
            // Fallback for unexpected channel counts
            Timber.w("Unexpected channel count in srcMat: ${srcMat.channels()}. Converting to RGB.")
            Imgproc.cvtColor(srcMat, srcMat, Imgproc.COLOR_BGR2RGB)
        }

        val tempMask = Mat()
        Utils.bitmapToMat(currentMask, tempMask)
        Imgproc.cvtColor(tempMask, maskMat, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.threshold(maskMat, maskMat, 10.0, 255.0, Imgproc.THRESH_BINARY)
        tempMask.release()

        // Floodmask must be 2 pixels wider and taller than the input image.
        val floodMask = Mat.zeros(srcMat.rows() + 2, srcMat.cols() + 2, CvType.CV_8UC1)

        val loDiff = Scalar(20.0, 20.0, 20.0)
        val upDiff = Scalar(20.0, 20.0, 20.0)

        val seed = Point(touchPoint.x.toDouble(), touchPoint.y.toDouble())

        // Flags: 4-connectivity, Fill value 255, Fixed range, Mask only
        val flags = 4 +
                (255 shl 8) +
                Imgproc.FLOODFILL_FIXED_RANGE +
                Imgproc.FLOODFILL_MASK_ONLY

        val rect = Rect()

        try {
            Imgproc.floodFill(
                srcMat, 
                floodMask, 
                seed, 
                Scalar(255.0, 255.0, 255.0), 
                rect, 
                loDiff, 
                upDiff, 
                flags
            )
        } catch (e: Exception) {
            Timber.e(e, "OpenCV floodFill failed")
            // Return the original mask on failure to avoid crashing
            return@withContext currentMask
        }

        // Extract the result from the floodmask (excluding the 1px border)
        val newRegion = floodMask.submat(1, srcMat.rows() + 1, 1, srcMat.cols() + 1)

        if (isAdding) {
            Core.bitwise_or(maskMat, newRegion, maskMat)
        } else {
            Core.bitwise_not(newRegion, newRegion)
            Core.bitwise_and(maskMat, newRegion, maskMat)
        }

        val resultBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(maskMat, resultBitmap)

        srcMat.release()
        maskMat.release()
        floodMask.release()
        newRegion.release()

        resultBitmap
    }

    /**
     * Extracts corners from a mask.
     */
    suspend fun extractCorners(mask: Bitmap): List<Offset> = withContext(Dispatchers.IO) {
        val maskMat = Mat()
        Utils.bitmapToMat(mask, maskMat)
        Imgproc.cvtColor(maskMat, maskMat, Imgproc.COLOR_RGBA2GRAY)
        
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(maskMat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        
        if (contours.isEmpty()) {
            return@withContext emptyList()
        }
        
        // Find the largest contour
        val largestContour = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return@withContext emptyList()
        
        // Approximate to a polygon
        val peri = Imgproc.arcLength(org.opencv.core.MatOfPoint2f(*largestContour.toArray()), true)
        val approx = org.opencv.core.MatOfPoint2f()
        Imgproc.approxPolyDP(org.opencv.core.MatOfPoint2f(*largestContour.toArray()), approx, 0.02 * peri, true)
        
        val corners = approx.toArray().map { Offset(it.x.toFloat(), it.y.toFloat()) }
        
        maskMat.release()
        hierarchy.release()
        
        corners
    }
}