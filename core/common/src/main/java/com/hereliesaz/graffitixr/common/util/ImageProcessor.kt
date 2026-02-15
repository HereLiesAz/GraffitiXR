package com.hereliesaz.graffitixr.common.util

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.room.jarjarred.org.antlr.v4.misc.Utils
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

object ImageProcessor {

    /**
     * Detects edges in the input bitmap using Canny edge detection and returns a line drawing style bitmap.
     * The output will be WHITE lines on a transparent background to allow tinting.
     *
     * @param bitmap The source bitmap.
     * @return A new bitmap containing the edge detection result.
     */
    fun detectEdges(bitmap: Bitmap): Bitmap? {
        return try {
            val src = Mat()
            val edges = Mat()
            val dest = Mat()
            val gray = Mat()
            val white = Mat()

            // Convert Bitmap to Mat
            Utils.bitmapToMat(bitmap, src)

            // Convert to grayscale
            if (src.channels() == 4) {
                Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            } else {
                Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGB2GRAY)
            }

            // Apply Canny Edge Detection
            Imgproc.Canny(gray, edges, 50.0, 150.0)

            // Create output Mat with Alpha channel (BGRA)
            // We want White lines (255,255,255) on Transparent background.
            // So RGB channels = 255, Alpha channel = edges (where edge is white/255 -> opaque)
            val rows = src.rows()
            val cols = src.cols()

            // Create a white image
            white.create(rows, cols, CvType.CV_8UC1)
            white.setTo(Scalar(255.0))

            // Merge into BGRA: B=white, G=white, R=white, A=edges
            val channels = ArrayList<Mat>()
            channels.add(white) // Blue
            channels.add(white) // Green
            channels.add(white) // Red
            channels.add(edges) // Alpha

            Core.merge(channels, dest)

            // Convert back to Bitmap
            val resultBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dest, resultBitmap)

            // Cleanup
            src.release()
            gray.release()
            edges.release()
            dest.release()
            white.release()

            resultBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Unwarps an image using a perspective transform based on 4 corner points.
     *
     * @param bitmap The source image.
     * @param points List of 4 Offsets representing the corners in normalized coordinates (0..1).
     *               Expected order: TL, TR, BR, BL.
     * @return The unwarped bitmap.
     */
    fun unwarpImage(bitmap: Bitmap, points: List<Offset>): Bitmap? {
        if (points.size != 4) return null

        return try {
            val src = Mat()
            Utils.bitmapToMat(bitmap, src)

            val w = src.width().toDouble()
            val h = src.height().toDouble()

            // Source points (normalized -> pixel)
            val srcPoints = MatOfPoint2f(
                Point(points[0].x.toDouble() * w, points[0].y.toDouble() * h),
                Point(points[1].x.toDouble() * w, points[1].y.toDouble() * h),
                Point(points[2].x.toDouble() * w, points[2].y.toDouble() * h),
                Point(points[3].x.toDouble() * w, points[3].y.toDouble() * h)
            )

            // Destination points (Rectified)
            val dstPoints = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(w, 0.0),
                Point(w, h),
                Point(0.0, h)
            )

            val perspectiveTransform = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
            val dest = Mat()

            Imgproc.warpPerspective(src, dest, perspectiveTransform, Size(w, h))

            val resultBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dest, resultBitmap)

            // Cleanup
            src.release()
            srcPoints.release()
            dstPoints.release()
            perspectiveTransform.release()
            dest.release()

            resultBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
