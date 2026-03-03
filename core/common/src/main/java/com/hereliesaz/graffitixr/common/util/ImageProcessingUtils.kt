// FILE: core/common/src/main/java/com/hereliesaz/graffitixr/common/util/ImageProcessingUtils.kt
package com.hereliesaz.graffitixr.common.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Fingerprint
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer

object ImageProcessingUtils {

    private const val TAG = "ImageProcessingUtils"

    private val threadLocalRgbaBuffer = object : ThreadLocal<java.nio.ByteBuffer>() {
        override fun initialValue(): java.nio.ByteBuffer? = null
    }
    private val threadLocalBufferSize = object : ThreadLocal<Int>() {
        override fun initialValue(): Int = 0
    }

    fun convertYuvToRgbaDirect(image: android.media.Image): java.nio.ByteBuffer {
        val width = image.width
        val height = image.height
        val requiredSize = width * height * 4

        var buffer = threadLocalRgbaBuffer.get()
        val currentSize = threadLocalBufferSize.get() ?: 0

        if (buffer == null || currentSize != requiredSize) {
            buffer = java.nio.ByteBuffer.allocateDirect(requiredSize)
            threadLocalRgbaBuffer.set(buffer)
            threadLocalBufferSize.set(requiredSize)
        }

        buffer!!.clear()

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        for (j in 0 until height) {
            for (i in 0 until width) {
                val yIndex = j * yRowStride + i
                val uvIndex = (j / 2) * uvRowStride + (i / 2) * uvPixelStride

                val y = (yBuffer.get(yIndex).toInt() and 0xFF) - 16
                val u = (uBuffer.get(uvIndex).toInt() and 0xFF) - 128
                val v = (vBuffer.get(uvIndex).toInt() and 0xFF) - 128

                val y1192 = 1192 * y.coerceAtLeast(0)
                var r = (y1192 + 1634 * v) shr 10
                var g = (y1192 - 833 * v - 400 * u) shr 10
                var b = (y1192 + 2066 * u) shr 10

                r = r.coerceIn(0, 255)
                g = g.coerceIn(0, 255)
                b = b.coerceIn(0, 255)

                buffer.put(r.toByte())
                buffer.put(g.toByte())
                buffer.put(b.toByte())
                buffer.put(0xFF.toByte())
            }
        }

        buffer.rewind()
        return buffer
    }

    fun applyAdjustments(bitmap: Bitmap, brightness: Float, contrast: Float, saturation: Float): Bitmap {
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
        cm.postConcat(ColorMatrix(contrastMatrix))
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    fun applyColorBalance(bitmap: Bitmap, red: Float, green: Float, blue: Float): Bitmap {
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
        val edges = Mat()
        val rgba = Mat()
        try {
            Utils.bitmapToMat(bitmap, mat)
            Imgproc.cvtColor(mat, edges, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(edges, edges, org.opencv.core.Size(5.0, 5.0), 1.5)
            Imgproc.Canny(edges, edges, 50.0, 150.0)

            val white = org.opencv.core.Scalar(255.0, 255.0, 255.0, 255.0)
            rgba.create(edges.size(), CvType.CV_8UC4)
            rgba.setTo(org.opencv.core.Scalar(0.0, 0.0, 0.0, 0.0))
            rgba.setTo(white, edges)

            val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgba, result)
            return result
        } finally {
            mat.release()
            edges.release()
            rgba.release()
        }
    }

    fun unwarpImage(bitmap: Bitmap, points: List<Offset>): Bitmap? {
        if (points.size != 4) return null
        val srcMat = Mat()
        val dstMat = Mat()
        val srcPoints = MatOfPoint2f()
        val dstPoints = MatOfPoint2f()
        val transform = Mat()

        try {
            Utils.bitmapToMat(bitmap, srcMat)
            val w = srcMat.cols().toDouble()
            val h = srcMat.rows().toDouble()

            srcPoints.fromArray(
                Point(points[0].x * w, points[0].y * h),
                Point(points[1].x * w, points[1].y * h),
                Point(points[2].x * w, points[2].y * h),
                Point(points[3].x * w, points[3].y * h)
            )
            dstPoints.fromArray(Point(0.0, 0.0), Point(w, 0.0), Point(w, h), Point(0.0, h))

            val pTransform = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
            pTransform.copyTo(transform)
            Imgproc.warpPerspective(srcMat, dstMat, transform, srcMat.size())

            val result = Bitmap.createBitmap(dstMat.cols(), dstMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dstMat, result)
            return result
        } finally {
            srcMat.release()
            dstMat.release()
            srcPoints.release()
            dstPoints.release()
            transform.release()
        }
    }

    fun generateFingerprint(bitmap: Bitmap): Fingerprint {
        return generateFingerprintWithDepth(bitmap, ByteBuffer.allocate(0), 0, 0, floatArrayOf(0f, 0f, 0f, 0f))
    }

    fun generateFingerprintWithDepth(bitmap: Bitmap, depthBuffer: ByteBuffer, depthWidth: Int, depthHeight: Int, intrinsics: FloatArray): Fingerprint {
        val mat = Mat()
        val gray = Mat()
        val orb = ORB.create()
        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        val emptyMask = Mat()

        try {
            Utils.bitmapToMat(bitmap, mat)
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY)
            orb.detectAndCompute(gray, emptyMask, keypoints, descriptors)

            val kpList = keypoints.toList()
            val points3d = ArrayList<Float>()

            val fx = intrinsics[0]
            val fy = intrinsics[1]
            val cx = intrinsics[2]
            val cy = intrinsics[3]

            val scaleX = if (bitmap.width > 0) depthWidth.toFloat() / bitmap.width else 0f
            val scaleY = if (bitmap.height > 0) depthHeight.toFloat() / bitmap.height else 0f

            depthBuffer.rewind()

            for (kp in kpList) {
                val u = kp.pt.x
                val v = kp.pt.y
                val dU = (u * scaleX).toInt().coerceIn(0, depthWidth - 1)
                val dV = (v * scaleY).toInt().coerceIn(0, depthHeight - 1)

                val index = (dV * depthWidth + dU) * 2
                if (depthWidth > 0 && index + 1 < depthBuffer.limit()) {
                    val dLow = depthBuffer.get(index).toInt() and 0xFF
                    val dHigh = depthBuffer.get(index + 1).toInt() and 0xFF
                    val depthMm = (dHigh shl 8) or dLow

                    if (depthMm > 0 && depthMm < 5000) {
                        val z = depthMm * 0.001f
                        val x = (u.toFloat() - cx) * z / fx
                        val y = (v.toFloat() - cy) * z / fy
                        points3d.add(x); points3d.add(y); points3d.add(z)
                    } else {
                        points3d.add(0f); points3d.add(0f); points3d.add(0f)
                    }
                } else {
                    points3d.add(0f); points3d.add(0f); points3d.add(0f)
                }
            }

            val data = ByteArray(descriptors.rows() * descriptors.cols() * descriptors.elemSize().toInt())
            if (data.isNotEmpty()) {
                descriptors.get(0, 0, data)
            }

            return Fingerprint(kpList, points3d, data, descriptors.rows(), descriptors.cols(), descriptors.type())
        } finally {
            mat.release(); gray.release(); keypoints.release(); descriptors.release(); emptyMask.release(); orb.clear()
        }
    }
}