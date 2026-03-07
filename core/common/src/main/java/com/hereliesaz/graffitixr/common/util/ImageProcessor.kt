// FILE: core/common/src/main/java/com/hereliesaz/graffitixr/common/util/ImageProcessor.kt
package com.hereliesaz.graffitixr.common.util

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max

object ImageProcessor {

    fun detectEdges(bitmap: Bitmap): Bitmap? {
        return try {
            val srcMat = Mat()
            Utils.bitmapToMat(bitmap, srcMat)

            val grayMat = Mat()
            Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_RGBA2GRAY)

            val edgesMat = Mat()
            Imgproc.Canny(grayMat, edgesMat, 50.0, 150.0)

            org.opencv.core.Core.bitwise_not(edgesMat, edgesMat)

            val dstMat = Mat()
            Imgproc.cvtColor(edgesMat, dstMat, Imgproc.COLOR_GRAY2RGBA)

            val resultBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dstMat, resultBitmap)

            srcMat.release()
            grayMat.release()
            edgesMat.release()
            dstMat.release()

            resultBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun unwarpImage(bitmap: Bitmap, points: List<Offset>): Bitmap? {
        if (points.size != 4) return null

        return try {
            val srcMat = Mat()
            Utils.bitmapToMat(bitmap, srcMat)

            val p0 = Point(points[0].x.toDouble(), points[0].y.toDouble()) // TL
            val p1 = Point(points[1].x.toDouble(), points[1].y.toDouble()) // TR
            val p2 = Point(points[2].x.toDouble(), points[2].y.toDouble()) // BR
            val p3 = Point(points[3].x.toDouble(), points[3].y.toDouble()) // BL

            val widthA = hypot(p2.x - p3.x, p2.y - p3.y)
            val widthB = hypot(p1.x - p0.x, p1.y - p0.y)
            val maxWidth = max(widthA, widthB).toInt()

            val heightA = hypot(p1.x - p2.x, p1.y - p2.y)
            val heightB = hypot(p0.x - p3.x, p0.y - p3.y)
            val maxHeight = max(heightA, heightB).toInt()

            if (maxWidth <= 0 || maxHeight <= 0) {
                srcMat.release()
                return null
            }

            val srcPts = MatOfPoint2f(p0, p1, p2, p3)
            val dstPts = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(maxWidth.toDouble() - 1, 0.0),
                Point(maxWidth.toDouble() - 1, maxHeight.toDouble() - 1),
                Point(0.0, maxHeight.toDouble() - 1)
            )

            val transform = Imgproc.getPerspectiveTransform(srcPts, dstPts)
            val dstMat = Mat()
            Imgproc.warpPerspective(srcMat, dstMat, transform, Size(maxWidth.toDouble(), maxHeight.toDouble()))

            val resultBitmap = Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dstMat, resultBitmap)

            srcMat.release()
            dstMat.release()
            transform.release()
            srcPts.release()
            dstPts.release()

            resultBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}