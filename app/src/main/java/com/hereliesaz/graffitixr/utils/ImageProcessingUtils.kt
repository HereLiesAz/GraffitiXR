package com.hereliesaz.graffitixr.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.hereliesaz.graffitixr.data.Fingerprint
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.ORB
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

// Top-level functions required by MainViewModel and ArRenderer

fun detectFeaturesWithMask(bitmap: Bitmap): List<org.opencv.core.KeyPoint> {
     val mat = Mat()
     val gray = Mat()
     val orb = ORB.create()
     val keypoints = MatOfKeyPoint()
     return try {
         Utils.bitmapToMat(bitmap, mat)
         Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY)
         orb.detect(gray, keypoints)
         keypoints.toList()
     } finally {
         mat.release()
         gray.release()
         keypoints.release()
         orb.clear() // Releases internal state (standard OpenCV Java binding for Algorithm)
     }
}

fun generateFingerprint(bitmap: Bitmap): Fingerprint {
     val mat = Mat()
     val gray = Mat()
     val orb = ORB.create()
     val keypoints = MatOfKeyPoint()
     val descriptors = Mat()
     val emptyMask = Mat()
     return try {
         Utils.bitmapToMat(bitmap, mat)
         Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY)
         orb.detectAndCompute(gray, emptyMask, keypoints, descriptors)

         val data = ByteArray(descriptors.rows() * descriptors.cols() * descriptors.elemSize().toInt())
         if (data.isNotEmpty()) {
             descriptors.get(0, 0, data)
         }

         Fingerprint(keypoints.toList(), data, descriptors.rows(), descriptors.cols(), descriptors.type())
     } finally {
         mat.release()
         gray.release()
         keypoints.release()
         descriptors.release()
         emptyMask.release()
         orb.clear() // Releases internal state (standard OpenCV Java binding for Algorithm)
     }
}

fun enhanceImageForAr(bitmap: Bitmap): Bitmap {
    val mat = Mat()
    val lab = Mat()
    val channels = ArrayList<Mat>()
    return try {
        Utils.bitmapToMat(bitmap, mat)
        Imgproc.cvtColor(mat, lab, Imgproc.COLOR_RGB2Lab)
        Core.split(lab, channels)
        val clahe = Imgproc.createCLAHE()
        clahe.clipLimit = 4.0
        if (channels.isNotEmpty()) {
            clahe.apply(channels[0], channels[0])
        }
        Core.merge(channels, lab)
        Imgproc.cvtColor(lab, mat, Imgproc.COLOR_Lab2RGB)
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, result)
        result
    } finally {
        mat.release()
        lab.release()
        channels.forEach { it.release() }
    }
}
