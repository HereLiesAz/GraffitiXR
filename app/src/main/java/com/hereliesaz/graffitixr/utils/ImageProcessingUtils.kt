package com.hereliesaz.graffitixr.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.data.Fingerprint
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc

object ImageProcessingUtils {

    fun applyAdjustments(
        bitmap: Bitmap,
        brightness: Float,
        contrast: Float,
        saturation: Float
    ): Bitmap {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()

        val cm = ColorMatrix()
        cm.setSaturation(saturation)
        
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

    fun applyColorBalance(
        bitmap: Bitmap,
        red: Float,
        green: Float,
        blue: Float
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

    fun createOutline(bitmap: Bitmap): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        val edges = Mat()
        Imgproc.cvtColor(mat, edges, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(edges, edges, org.opencv.core.Size(5.0, 5.0), 1.5)
        Imgproc.Canny(edges, edges, 50.0, 150.0)
        
        val white = org.opencv.core.Scalar(255.0, 255.0, 255.0, 255.0)
        val rgba = Mat(edges.size(), CvType.CV_8UC4, org.opencv.core.Scalar(0.0, 0.0, 0.0, 0.0))
        rgba.setTo(white, edges)
        
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, result)
        
        mat.release()
        edges.release()
        rgba.release()
        return result
    }

    /**
     * Rectifies a perspective-distorted image based on 4 normalized corner points.
     */
    fun unwarpImage(bitmap: Bitmap, points: List<Offset>): Bitmap? {
        if (points.size != 4) return null

        val srcMat = Mat()
        Utils.bitmapToMat(bitmap, srcMat)

        val w = srcMat.cols().toDouble()
        val h = srcMat.rows().toDouble()

        // 1. Define Source Points (User's Quad)
        val srcPoints = MatOfPoint2f(
            Point(points[0].x * w, points[0].y * h), // TL
            Point(points[1].x * w, points[1].y * h), // TR
            Point(points[2].x * w, points[2].y * h), // BR
            Point(points[3].x * w, points[3].y * h)  // BL
        )

        // 2. Define Destination Points (Rectangular Image)
        val dstPoints = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(w, 0.0),
            Point(w, h),
            Point(0.0, h)
        )

        // 3. Compute Perspective Transform
        val transform = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
        val dstMat = Mat()

        // 4. Apply Transform
        Imgproc.warpPerspective(srcMat, dstMat, transform, srcMat.size())

        val result = Bitmap.createBitmap(dstMat.cols(), dstMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dstMat, result)

        srcMat.release()
        dstMat.release()
        srcPoints.release()
        dstPoints.release()
        transform.release()

        return result
    }
}

// Top-level functions
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
         orb.clear()
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
         orb.clear()
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
