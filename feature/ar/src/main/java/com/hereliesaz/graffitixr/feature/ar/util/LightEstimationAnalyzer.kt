package com.hereliesaz.graffitixr.feature.ar.util

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * An ImageAnalysis.Analyzer that computes the average luminosity of the frame.
 * Used to update the virtual lighting environment when ARCore is unavailable.
 */
class LightEstimationAnalyzer(private val listener: (Float) -> Unit) : ImageAnalysis.Analyzer {

    private var lastAnalyzedTimestamp = 0L
    // Limit analysis frequency to ~5 FPS to save battery/CPU
    private val frameIntervalMs = 200L

    override fun analyze(image: ImageProxy) {
        val currentTimestamp = System.currentTimeMillis()
        if (currentTimestamp - lastAnalyzedTimestamp < frameIntervalMs) {
            image.close()
            return
        }

        lastAnalyzedTimestamp = currentTimestamp

        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height

        var sum = 0L
        var count = 0

        // Iterate through rows
        // We skip pixels for performance (every 20th pixel)
        val skip = 20

        for (row in 0 until height step skip) {
            val rowStart = row * rowStride
            for (col in 0 until width step skip) {
                // Calculate index for the pixel
                val index = rowStart + (col * pixelStride)

                // Ensure we don't go out of bounds (though strides should prevent this)
                if (index < buffer.limit()) {
                    sum += (buffer.get(index).toInt() and 0xFF)
                    count++
                }
            }
        }

        val average = if (count > 0) sum.toDouble() / count else 0.0
        val normalizedLuma = (average / 255.0).toFloat()

        listener(normalizedLuma)
        image.close()
    }
}
