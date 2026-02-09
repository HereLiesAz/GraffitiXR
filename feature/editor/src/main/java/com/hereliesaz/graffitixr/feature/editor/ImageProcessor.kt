package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
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
}
