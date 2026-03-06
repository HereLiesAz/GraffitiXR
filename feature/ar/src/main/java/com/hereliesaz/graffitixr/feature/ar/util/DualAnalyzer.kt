// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/util/DualAnalyzer.kt
package com.hereliesaz.graffitixr.feature.ar.util

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * Handles Light Estimation for virtual overlays.
 * NOTE: The CPU-heavy Teleological OpenCV Bitmap extraction was removed!
 * The C++ MobileGS engine now performs relocalization directly on the
 * RGBA buffer passed by ArRenderer natively, saving immense overhead.
 */
class DualAnalyzer(
    private val onLightUpdate: (Float) -> Unit,
    private val onSlamFrame: ((ByteBuffer, Int, Int, Long) -> Unit)? = null,
    private val clock: () -> Long = System::currentTimeMillis
) : ImageAnalysis.Analyzer {

    private val lightIntervalMs = 200L
    private var lastLightUpdate = -lightIntervalMs

    override fun analyze(image: ImageProxy) {
        val now = clock()

        // 1. SLAM Tracking (Every Frame - primarily for Stereo Depth fallback)
        if (onSlamFrame != null && image.planes.isNotEmpty()) {
            val yBuffer = image.planes[0].buffer
            onSlamFrame.invoke(yBuffer, image.width, image.height, image.imageInfo.timestamp)
        }

        // 2. Light Estimation
        if (now - lastLightUpdate >= lightIntervalMs) {
            lastLightUpdate = now
            estimateLight(image)
        }

        image.close()
    }

    private fun estimateLight(image: ImageProxy) {
        val buffer = image.planes[0].buffer
        val pixelStride = image.planes[0].pixelStride
        val rowStride = image.planes[0].rowStride
        var sum = 0L
        var count = 0

        val skip = 100

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
}