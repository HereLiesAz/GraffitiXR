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

        val buffer = image.planes[0].buffer

        // Compute average pixel value
        // We subsample for performance (skip every 50 pixels)
        var sum = 0L
        val pixelStride = 50
        var count = 0

        // Direct buffer access without allocation
        val limit = buffer.limit()
        for (i in 0 until limit step pixelStride) {
            sum += (buffer.get(i).toInt() and 0xFF)
            count++
        }

        val average = if (count > 0) sum.toDouble() / count else 0.0
        val normalizedLuma = (average / 255.0).toFloat()

        listener(normalizedLuma)
        image.close()
    }
}
