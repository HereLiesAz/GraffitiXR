package com.hereliesaz.graffitixr.feature.ar.util

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * Combines Light Estimation (fast, frequent) and Teleological tracking (slow, infrequent)
 * to prevent having multiple CameraX ImageAnalysis use-cases fighting for resources.
 */
class DualAnalyzer(
    private val onLightUpdate: (Float) -> Unit,
    private val onTeleologicalFrame: (Bitmap) -> Unit,
    private val onSlamFrame: ((ByteBuffer, Int, Int) -> Unit)? = null
) : ImageAnalysis.Analyzer {

    private var lastLightUpdate = 0L
    private var lastTeleologicalUpdate = 0L

    // Limits
    private val lightIntervalMs = 200L
    private val teleologicalIntervalMs = 1500L // 1.5 seconds

    override fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()

        // 0. SLAM Tracking (Every Frame)
        if (onSlamFrame != null && image.planes.isNotEmpty()) {
            val yBuffer = image.planes[0].buffer
            // Provide raw Y-buffer to native SLAM
            onSlamFrame.invoke(yBuffer, image.width, image.height)
        }

        // 1. Light Estimation
        if (now - lastLightUpdate >= lightIntervalMs) {
            lastLightUpdate = now
            estimateLight(image)
        }

        // 2. Teleological Tracking (Only trigger if JPEG/YUV format is valid)
        if (now - lastTeleologicalUpdate >= teleologicalIntervalMs) {
            lastTeleologicalUpdate = now
            if (image.format == ImageFormat.YUV_420_888 || image.format == ImageFormat.JPEG) {
                // Warning: Converting YUV to Bitmap in real-time is expensive.
                // For a robust production app, TeleologicalTracker should ingest YUV directly into OpenCV.
                // For this beta, we extract the Y-plane (grayscale) for the ORB feature matcher if possible.
                extractGrayscaleBitmap(image)?.let { bmp ->
                    onTeleologicalFrame(bmp)
                }
            }
        }

        image.close()
    }

    private fun estimateLight(image: ImageProxy) {
        val buffer = image.planes[0].buffer
        val pixelStride = image.planes[0].pixelStride
        val rowStride = image.planes[0].rowStride
        var sum = 0L
        var count = 0
        val skip = 20

        for (row in 0 until image.height step skip) {
            val rowStart = row * rowStride
            for (col in 0 until image.width step skip) {
                val index = rowStart + (col * pixelStride)
                if (index < buffer.limit()) {
                    sum += (buffer.get(index).toInt() and 0xFF)
                    count++
                }
            }
        }
        val average = if (count > 0) sum.toDouble() / count else 0.0
        onLightUpdate((average / 255.0).toFloat())
    }

    private fun extractGrayscaleBitmap(image: ImageProxy): Bitmap? {
        return try {
            val yPlane = image.planes[0]
            val yBuffer = yPlane.buffer
            val rowStride = yPlane.rowStride
            val pixelStride = yPlane.pixelStride
            val width = image.width
            val height = image.height

            val cleanBytes: ByteArray
            if (pixelStride == 1 && rowStride == width) {
                // Fast path: direct copy
                cleanBytes = ByteArray(yBuffer.remaining())
                yBuffer.get(cleanBytes)
            } else {
                // Handle stride padding
                cleanBytes = ByteArray(width * height)
                for (row in 0 until height) {
                    yBuffer.position(row * rowStride)
                    yBuffer.get(cleanBytes, row * width, width)
                }
            }

            // Create a grayscale bitmap directly from the Y channel
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(cleanBytes))

            // Apply rotation
            val rotation = image.imageInfo.rotationDegrees
            if (rotation != 0) {
                val matrix = Matrix()
                matrix.postRotate(rotation.toFloat())
                Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}