// FILE: core/common/src/main/java/com/hereliesaz/graffitixr/common/util/ImageProcessor.kt
package com.hereliesaz.graffitixr.common.util

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.ArrayList
import kotlin.math.min
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale

object ImageProcessor {

    private const val MAX_DIMENSION = 2048

    private fun resizeIfTooLarge(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= MAX_DIMENSION && bitmap.height <= MAX_DIMENSION) return bitmap
        val ratio = min(MAX_DIMENSION.toFloat() / bitmap.width, MAX_DIMENSION.toFloat() / bitmap.height)
        val width = (bitmap.width * ratio).toInt()
        val height = (bitmap.height * ratio).toInt()
        return bitmap.scale(width, height)
    }

    fun detectEdges(bitmap: Bitmap): Bitmap? {
        val src = Mat()
        val edges = Mat()
        val dest = Mat()
        val gray = Mat()
        val white = Mat()
        var safeBitmap: Bitmap? = null

        try {
            safeBitmap = resizeIfTooLarge(bitmap)
            Utils.bitmapToMat(safeBitmap, src)

            if (src.channels() == 4) {
                Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            } else {
                Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGB2GRAY)
            }

            Imgproc.Canny(gray, edges, 50.0, 150.0)

            val rows = src.rows()
            val cols = src.cols()

            white.create(rows, cols, CvType.CV_8UC1)
            white.setTo(Scalar(255.0))

            val channels = ArrayList<Mat>()
            channels.add(white)
            channels.add(white)
            channels.add(white)
            channels.add(edges)

            Core.merge(channels, dest)

            val resultBitmap = createBitmap(safeBitmap.width, safeBitmap.height)
            Utils.matToBitmap(dest, resultBitmap)
            return resultBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            src.release(); gray.release(); edges.release(); dest.release(); white.release()
            if (safeBitmap != null && safeBitmap !== bitmap) {
                safeBitmap.recycle()
            }
        }
    }

    fun unwarpImage(bitmap: Bitmap, points: List<Offset>): Bitmap? {
        if (points.size != 4) return null
        val src = Mat()
        val dest = Mat()
        val srcPoints = MatOfPoint2f()
        val dstPoints = MatOfPoint2f()
        val perspectiveTransform = Mat()
        var safeBitmap: Bitmap? = null

        try {
            safeBitmap = resizeIfTooLarge(bitmap)
            Utils.bitmapToMat(safeBitmap, src)
            val w = src.width().toDouble()
            val h = src.height().toDouble()

            srcPoints.fromArray(
                Point(points[0].x.toDouble() * w, points[0].y.toDouble() * h),
                Point(points[1].x.toDouble() * w, points[1].y.toDouble() * h),
                Point(points[2].x.toDouble() * w, points[2].y.toDouble() * h),
                Point(points[3].x.toDouble() * w, points[3].y.toDouble() * h)
            )
            dstPoints.fromArray(Point(0.0, 0.0), Point(w, 0.0), Point(w, h), Point(0.0, h))

            val pTransform = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
            pTransform.copyTo(perspectiveTransform)

            Imgproc.warpPerspective(src, dest, perspectiveTransform, Size(w, h))

            val resultBitmap = createBitmap(safeBitmap.width, safeBitmap.height)
            Utils.matToBitmap(dest, resultBitmap)
            return resultBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            src.release(); srcPoints.release(); dstPoints.release(); perspectiveTransform.release(); dest.release()
            if (safeBitmap != null && safeBitmap !== bitmap) {
                safeBitmap.recycle()
            }
        }
    }
}