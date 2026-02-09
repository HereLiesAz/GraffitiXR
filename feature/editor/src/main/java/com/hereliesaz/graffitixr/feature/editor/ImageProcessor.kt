package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

object ImageProcessor {

    /**
     * Detects edges in the input bitmap using Canny edge detection and returns a line drawing style bitmap.
     * The output will be inverted (black lines on white background) for a sketch effect.
     *
     * @param bitmap The source bitmap.
     * @return A new bitmap containing the edge detection result.
     */
    fun detectEdges(bitmap: Bitmap): Bitmap? {
        return try {
            val src = Mat()
            val edges = Mat()
            val inverted = Mat()

            // Convert Bitmap to Mat
            Utils.bitmapToMat(bitmap, src)

            // Convert to grayscale
            val gray = Mat()
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGB2GRAY)

            // Apply Canny Edge Detection
            // Thresholds can be tuned. 50/150 is a common starting point.
            Imgproc.Canny(gray, edges, 50.0, 150.0)

            // Invert edges (Canny gives white edges on black, we want black on white usually)
            // Or maybe white on black is better for overlays?
            // "Render the image into a line drawing" usually implies dark lines on light background.
            // But if it's an overlay mode (Trace), maybe white lines on black is better?
            // Let's stick to inverting for a "drawing" look on white paper, or standard Canny for "glowing edges".
            // Since it's "Trace Mode" often used for tracing onto a wall, high contrast is key.
            // White lines on black is standard for Canny.
            // If the user wants a "line drawing" (like a sketch), usually it's black lines.
            // Let's produce White lines on Black (standard Canny result) as it overlays better in AR/dark mode.
            // Wait, "line drawing" implies sketch.
            // Let's invert it: Black lines on Transparent/White?
            // For now, let's just return the Canny result (White on Black) as it is most robust.
            // To make it look like a sketch on white paper, we would invert.
            // Let's invert it to be safe for "outline" request.
            Core.bitwise_not(edges, inverted)

            // Convert back to Bitmap
            // We need a bitmap of the same size
            val resultBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(inverted, resultBitmap)

            // Cleanup
            src.release()
            gray.release()
            edges.release()
            inverted.release()

            resultBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
