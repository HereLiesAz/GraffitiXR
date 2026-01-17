package com.hereliesaz.graffitixr.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import org.opencv.android.Utils
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
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()

        val cm = ColorMatrix()
        
        // Saturation
        cm.setSaturation(saturation)
        
        // Contrast & Brightness (Scale and Translate)
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
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
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
     */
    fun createOutline(bitmap: Bitmap): Bitmap {
        ensureOpenCVLoaded()
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        
        val edges = Mat()
        Imgproc.cvtColor(mat, edges, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(edges, edges, org.opencv.core.Size(5.0, 5.0), 1.5)
        Imgproc.Canny(edges, edges, 50.0, 150.0)
        
        val rgba = Mat(edges.size(), CvType.CV_8UC4, org.opencv.core.Scalar(0.0, 0.0, 0.0, 0.0))
        val white = org.opencv.core.Scalar(255.0, 255.0, 255.0, 255.0)
        rgba.setTo(white, edges)
        
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, result)
        
        mat.release()
        edges.release()
        rgba.release()
        
        return result
    }
}
