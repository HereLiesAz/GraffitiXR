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

    fun detectEdges(bitmap: Bitmap): Bitmap? {
        val src = Mat()
        val edges = Mat()
        val dest = Mat()
        val gray = Mat()
        val white = Mat()

        return try {
            Utils.bitmapToMat(bitmap, src)

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

            val resultBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dest, resultBitmap)

            resultBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } catch (e: OutOfMemoryError) {
            e.printStackTrace()
            null
        } finally {
            src.release()
            gray.release()
            edges.release()
            dest.release()
            white.release()
        }
    }
}