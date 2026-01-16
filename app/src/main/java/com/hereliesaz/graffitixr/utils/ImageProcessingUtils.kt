package com.hereliesaz.graffitixr.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * A collection of static methods to manipulate reality, pixel by pixel.
 */
object ImageProcessingUtils {

    /**
     * Applies basic adjustments: Brightness, Contrast, Saturation.
     * It's the standard triad of image manipulation.
     */
    fun applyAdjustments(
        bitmap: Bitmap,
        brightness: Float, // 0.0 to 2.0, default 1.0
        contrast: Float,   // 0.0 to 2.0, default 1.0
        saturation: Float  // 0.0 to 2.0, default 1.0
    ): Bitmap {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config)
        val canvas = Canvas(result)
        val paint = Paint()

        val cm = ColorMatrix()
        
        // Saturation
        cm.setSaturation(saturation)
        
        // Contrast & Brightness (Scale and Translate)
        // This is a simplified matrix application. 
        // Real contrast pivots around gray (128), but let's keep it raw.
        val contrastMatrix = floatArrayOf(
            contrast, 0f, 0f, 0f, (1f - contrast) * 128f + (brightness - 1f) * 255f,
            0f, contrast, 0f, 0f, (1f - contrast) * 128f + (brightness - 1f) * 255f,
            0f, 0f, contrast, 0f, (1f - contrast) * 128f + (brightness - 1f) * 255f,
            0f, 0f, 0f, 1f, 0f
        )
        
        val adjustMatrix = ColorMatrix(contrastMatrix)
        cm.postConcat(adjustMatrix)

        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        
        return result
    }

    /**
     * Applies Color Balance. Because sometimes reality is too green.
     */
    fun applyColorBalance(
        bitmap: Bitmap,
        red: Float,   // 0.0 to 2.0, default 1.0
        green: Float, // 0.0 to 2.0, default 1.0
        blue: Float   // 0.0 to 2.0, default 1.0
    ): Bitmap {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config)
        val canvas = Canvas(result)
        val paint = Paint()

        val cm = ColorMatrix(floatArrayOf(
            red, 0f, 0f, 0f, 0f,
            0f, green, 0f, 0f, 0f,
            0f, 0f, blue, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))

        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    /**
     * Generates an outline of the image content.
     * Uses Canny edge detection because we like our edges sharp and our context vague.
     */
    fun createOutline(bitmap: Bitmap): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        
        val edges = Mat()
        
        // Convert to grayscale
        Imgproc.cvtColor(mat, edges, Imgproc.COLOR_RGBA2GRAY)
        
        // Blur to remove noise (the static of existence)
        Imgproc.GaussianBlur(edges, edges, org.opencv.core.Size(5.0, 5.0), 1.5)
        
        // Detect edges
        Imgproc.Canny(edges, edges, 50.0, 150.0)
        
        // Invert so edges are black on white (or white on transparent, depending on need)
        // Let's make it White edges on Transparent for the overlay feel.
        val dest = Mat(edges.size(), CvType.CV_8UC4)
        Core.bitwise_not(edges, edges) // White background, black lines
        
        // Make simple conversion back to bitmap for now, assuming standard processing
        // Ideally we map the single channel edge data to the Alpha channel of a white image
        // But for "Outline Rail Item", a simple Canny usually suffices.
        
        // Actually, let's do White Lines on Transparent Background.
        // Canny gives 255 for edge, 0 for non-edge.
        // We want 255,255,255,255 for edge, and 0,0,0,0 for non-edge.
        
        // Reload raw edges (undo the bitwise_not if we did it)
        Imgproc.Canny(mat, edges, 50.0, 150.0) // Redo to be sure
        
        val rgba = Mat(edges.size(), CvType.CV_8UC4, org.opencv.core.Scalar(0.0, 0.0, 0.0, 0.0))
        val white = org.opencv.core.Scalar(255.0, 255.0, 255.0, 255.0)
        
        // Where edges != 0, set to white.
        rgba.setTo(white, edges)
        
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, result)
        
        mat.release()
        edges.release()
        rgba.release()
        
        return result
    }
}
