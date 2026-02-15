package com.hereliesaz.graffitixr.feature.ar.computervision

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.util.Collections
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TargetEvolutionEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val segmenter by lazy {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundBitmap()
            .build()
        SubjectSegmentation.getClient(options)
    }

    suspend fun initialGuess(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        suspendCancellableCoroutine { continuation ->
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            segmenter.process(inputImage)
                .addOnSuccessListener { result ->
                    val foreground = result.foregroundBitmap
                    if (foreground != null) {
                        continuation.resume(foreground)
                    } else {
                        val blank = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                        continuation.resume(blank)
                    }
                }
                .addOnFailureListener { e ->
                    continuation.resumeWithException(e)
                }
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

        Utils.bitmapToMat(image, srcMat)

        val tempMask = Mat()
        Utils.bitmapToMat(currentMask, tempMask)
        Imgproc.cvtColor(tempMask, maskMat, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.threshold(maskMat, maskMat, 10.0, 255.0, Imgproc.THRESH_BINARY)
        tempMask.release()

        val floodMask = Mat.zeros(srcMat.rows() + 2, srcMat.cols() + 2, CvType.CV_8UC1)

        val loDiff = Scalar(10.0, 10.0, 10.0)
        val upDiff = Scalar(10.0, 10.0, 10.0)

        val seed = Point(touchPoint.x.toDouble(), touchPoint.y.toDouble())

        val flags = 4 +
                (255 shl 8) +
                Imgproc.FLOODFILL_FIXED_RANGE +
                Imgproc.FLOODFILL_MASK_ONLY

        // FIX: Using org.opencv.core.Rect instead of android.graphics.Rect
        val rect = Rect()

        Imgproc.floodFill(srcMat, floodMask, seed, Scalar(255.0, 255.0, 255.0), rect, loDiff, upDiff, flags)

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

    suspend fun extractCorners(mask: Bitmap): List<Offset> = withContext(Dispatchers.Default) {
        val maskMat = Mat()
        Utils.bitmapToMat(mask, maskMat)
        Imgproc.cvtColor(maskMat, maskMat, Imgproc.COLOR_RGB2GRAY)

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()

        Imgproc.findContours(maskMat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        if (contours.isEmpty()) {
            maskMat.release()
            hierarchy.release()
            return@withContext listOf(
                Offset(0.1f, 0.1f), Offset(0.9f, 0.1f),
                Offset(0.9f, 0.9f), Offset(0.1f, 0.9f)
            )
        }

        val largestContour = Collections.max(contours) { c1, c2 ->
            Imgproc.contourArea(c1).compareTo(Imgproc.contourArea(c2))
        }

        val dst = MatOfPoint2f()
        val src = MatOfPoint2f()
        largestContour.convertTo(src, CvType.CV_32F)

        val epsilon = 0.02 * Imgproc.arcLength(src, true)
        Imgproc.approxPolyDP(src, dst, epsilon, true)

        val result = if (dst.toList().size == 4) {
            dst.toList().map { Offset(it.x.toFloat() / mask.width, it.y.toFloat() / mask.height) }
        } else {
            val rect = Imgproc.boundingRect(largestContour)
            listOf(
                Offset(rect.x.toFloat() / mask.width, rect.y.toFloat() / mask.height),
                Offset((rect.x + rect.width).toFloat() / mask.width, rect.y.toFloat() / mask.height),
                Offset((rect.x + rect.width).toFloat() / mask.width, (rect.y + rect.height).toFloat() / mask.height),
                Offset(rect.x.toFloat() / mask.width, (rect.y + rect.height).toFloat() / mask.height)
            )
        }

        maskMat.release()
        hierarchy.release()
        src.release()
        dst.release()

        val sortedByY = result.sortedBy { it.y }
        val top = sortedByY.take(2).sortedBy { it.x }
        val bottom = sortedByY.takeLast(2).sortedByDescending { it.x }

        top + bottom
    }
}
