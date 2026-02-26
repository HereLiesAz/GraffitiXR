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

object ImageProcessor {

    private const val MAX_DIMENSION = 2048

    private fun resizeIfTooLarge(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= MAX_DIMENSION && bitmap.height <= MAX_DIMENSION) return bitmap

        val ratio = min(
            MAX_DIMENSION.toFloat() / bitmap.width,
            MAX_DIMENSION.toFloat() / bitmap.height
        )
        val width = (bitmap.width * ratio).toInt()
        val height = (bitmap.height * ratio).toInt()

        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    /**
     * Detects edges in the input bitmap using Canny edge detection.
     * Automatically downsamples large images to prevent OOM.
     */
    fun detectEdges(bitmap: Bitmap): Bitmap? {
        return try {
            val safeBitmap = resizeIfTooLarge(bitmap)
            val src = Mat()
            val edges = Mat()
            val dest = Mat()
            val gray = Mat()
            val white = Mat()

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
            channels.add(white) // Blue
            channels.add(white) // Green
            channels.add(white) // Red
            channels.add(edges) // Alpha

            Core.merge(channels, dest)

            val resultBitmap = Bitmap.createBitmap(safeBitmap.width, safeBitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dest, resultBitmap)

            src.release()
            gray.release()
            edges.release()
            dest.release()
            white.release()

            // Only recycle if we created a scaled copy
            if (safeBitmap !== bitmap) {
                safeBitmap.recycle()
            }

            resultBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun unwarpImage(bitmap: Bitmap, points: List<Offset>): Bitmap? {
        if (points.size != 4) return null

        return try {
            val safeBitmap = resizeIfTooLarge(bitmap)
            val src = Mat()
            Utils.bitmapToMat(safeBitmap, src)

            val w = src.width().toDouble()
            val h = src.height().toDouble()

            val srcPoints = MatOfPoint2f(
                Point(points[0].x.toDouble() * w, points[0].y.toDouble() * h),
                Point(points[1].x.toDouble() * w, points[1].y.toDouble() * h),
                Point(points[2].x.toDouble() * w, points[2].y.toDouble() * h),
                Point(points[3].x.toDouble() * w, points[3].y.toDouble() * h)
            )

            val dstPoints = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(w, 0.0),
                Point(w, h),
                Point(0.0, h)
            )

            val perspectiveTransform = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
            val dest = Mat()

            Imgproc.warpPerspective(src, dest, perspectiveTransform, Size(w, h))

            val resultBitmap = Bitmap.createBitmap(safeBitmap.width, safeBitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dest, resultBitmap)

            src.release()
            srcPoints.release()
            dstPoints.release()
            perspectiveTransform.release()
            dest.release()

            if (safeBitmap !== bitmap) {
                safeBitmap.recycle()
            }

            resultBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}