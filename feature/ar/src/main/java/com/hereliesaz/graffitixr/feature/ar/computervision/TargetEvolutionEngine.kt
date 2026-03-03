// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/computervision/TargetEvolutionEngine.kt
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

@Singleton
class TargetEvolutionEngine @Inject constructor() {

    suspend fun initialGuess(image: Bitmap): Bitmap = withContext(Dispatchers.IO) {
        val maskMat = Mat.zeros(image.height, image.width, CvType.CV_8UC1)
        try {
            val rect = Rect(image.width / 4, image.height / 4, image.width / 2, image.height / 2)
            Imgproc.rectangle(maskMat, rect, Scalar(255.0), -1)

            val resultBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(maskMat, resultBitmap)
            return@withContext resultBitmap
        } finally {
            maskMat.release()
        }
    }

    suspend fun refineMask(
        image: Bitmap,
        currentMask: Bitmap,
        touchPoint: Offset,
        isAdding: Boolean
    ): Bitmap = withContext(Dispatchers.IO) {
        val srcMat = Mat()
        val maskMat = Mat()
        val tempMask = Mat()
        val floodMask = Mat()
        val newRegion = Mat()

        try {
            Utils.bitmapToMat(image, srcMat)

            if (srcMat.channels() == 4) {
                Imgproc.cvtColor(srcMat, srcMat, Imgproc.COLOR_RGBA2RGB)
            } else if (srcMat.channels() != 1 && srcMat.channels() != 3) {
                Timber.w("Unexpected channel count in srcMat: ${srcMat.channels()}. Converting to RGB.")
                Imgproc.cvtColor(srcMat, srcMat, Imgproc.COLOR_BGR2RGB)
            }

            Utils.bitmapToMat(currentMask, tempMask)
            Imgproc.cvtColor(tempMask, maskMat, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.threshold(maskMat, maskMat, 10.0, 255.0, Imgproc.THRESH_BINARY)

            floodMask.create(srcMat.rows() + 2, srcMat.cols() + 2, CvType.CV_8UC1)
            floodMask.setTo(Scalar(0.0))

            val loDiff = Scalar(20.0, 20.0, 20.0)
            val upDiff = Scalar(20.0, 20.0, 20.0)
            val seed = Point(touchPoint.x.toDouble(), touchPoint.y.toDouble())
            val flags = 4 + (255 shl 8) + Imgproc.FLOODFILL_FIXED_RANGE + Imgproc.FLOODFILL_MASK_ONLY
            val rect = Rect()

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

            val roi = floodMask.submat(1, srcMat.rows() + 1, 1, srcMat.cols() + 1)
            roi.copyTo(newRegion)

            if (isAdding) {
                Core.bitwise_or(maskMat, newRegion, maskMat)
            } else {
                Core.bitwise_not(newRegion, newRegion)
                Core.bitwise_and(maskMat, newRegion, maskMat)
            }

            val resultBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(maskMat, resultBitmap)
            return@withContext resultBitmap

        } catch (e: Exception) {
            Timber.e(e, "OpenCV floodFill failed")
            return@withContext currentMask
        } finally {
            srcMat.release(); maskMat.release(); tempMask.release(); floodMask.release(); newRegion.release()
        }
    }

    suspend fun extractCorners(mask: Bitmap): List<Offset> = withContext(Dispatchers.IO) {
        val maskMat = Mat()
        val hierarchy = Mat()
        try {
            Utils.bitmapToMat(mask, maskMat)
            Imgproc.cvtColor(maskMat, maskMat, Imgproc.COLOR_RGBA2GRAY)

            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(maskMat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            if (contours.isEmpty()) return@withContext emptyList()

            val largestContour = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return@withContext emptyList()

            val peri = Imgproc.arcLength(org.opencv.core.MatOfPoint2f(*largestContour.toArray()), true)
            val approx = org.opencv.core.MatOfPoint2f()
            Imgproc.approxPolyDP(org.opencv.core.MatOfPoint2f(*largestContour.toArray()), approx, 0.02 * peri, true)

            return@withContext approx.toArray().map { Offset(it.x.toFloat(), it.y.toFloat()) }
        } finally {
            maskMat.release()
            hierarchy.release()
        }
    }

    suspend fun snapToTarget(mask: Bitmap): List<Offset> = withContext(Dispatchers.IO) {
        val maskMat = Mat()
        val hierarchy = Mat()
        var contour2f = org.opencv.core.MatOfPoint2f()
        try {
            Utils.bitmapToMat(mask, maskMat)
            Imgproc.cvtColor(maskMat, maskMat, Imgproc.COLOR_RGBA2GRAY)

            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(maskMat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            if (contours.isEmpty()) return@withContext emptyList()

            val largestContour = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return@withContext emptyList()
            contour2f = org.opencv.core.MatOfPoint2f(*largestContour.toArray())
            val peri = Imgproc.arcLength(contour2f, true)

            var approx = org.opencv.core.MatOfPoint2f()
            var epsilon = 0.02 * peri
            var found = false

            for (i in 0 until 10) {
                Imgproc.approxPolyDP(contour2f, approx, epsilon, true)
                val count = approx.total().toInt()
                if (count == 4) {
                    found = true
                    break
                } else if (count > 4) {
                    epsilon *= 1.2
                } else {
                    epsilon *= 0.8
                }
            }

            return@withContext approx.toArray().map { Offset(it.x.toFloat(), it.y.toFloat()) }
        } finally {
            maskMat.release()
            hierarchy.release()
            contour2f.release()
        }
    }
}