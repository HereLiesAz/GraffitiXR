package com.hereliesaz.graffitixr.feature.ar.anchor

import java.util.ArrayDeque

/**
 * Short timestamped history of ARCore solve-time geometry for asynchronous KPM observations.
 *
 * KPM runs on its own worker. Using the render frame's view/backbone when its result arrives turns
 * ordinary hand motion during matcher latency into a false wall correction. Each submitted camera
 * image carries the sensor timestamp; this history resolves the ARCore view and consensus backbone
 * from that same instant (or a tightly bounded nearest sample).
 */
class HybridPoseHistory(
    private val capacity: Int = 48,
) {
    data class Sample(
        val timestampNs: Long,
        val viewMatrix: FloatArray,
        val backboneMatrix: FloatArray,
    ) {
        init {
            require(viewMatrix.size == 16 && backboneMatrix.size == 16)
        }
    }

    private val samples = ArrayDeque<Sample>(capacity)

    init {
        require(capacity >= 2)
    }

    fun clear() = samples.clear()

    fun add(timestampNs: Long, viewMatrix: FloatArray, backboneMatrix: FloatArray) {
        if (timestampNs <= 0L || viewMatrix.size != 16 || backboneMatrix.size != 16) return
        if (viewMatrix.any { !it.isFinite() } || backboneMatrix.any { !it.isFinite() }) return
        if (samples.isNotEmpty() && timestampNs < samples.last.timestampNs) {
            // ARCore sensor timestamps should be monotonic. A discontinuity means this history no
            // longer describes one clock sequence, so fail closed instead of cross-pairing epochs.
            samples.clear()
        }
        samples.addLast(
            Sample(timestampNs, viewMatrix.copyOf(), backboneMatrix.copyOf())
        )
        while (samples.size > capacity) samples.removeFirst()
    }

    fun nearest(timestampNs: Long, maxDeltaNs: Long): Sample? {
        if (timestampNs <= 0L || maxDeltaNs < 0L || samples.isEmpty()) return null
        var best: Sample? = null
        var bestDelta = Long.MAX_VALUE
        for (sample in samples) {
            val delta = absDiff(sample.timestampNs, timestampNs)
            if (delta < bestDelta) {
                best = sample
                bestDelta = delta
            }
        }
        return best?.takeIf { bestDelta <= maxDeltaNs }
    }

    private fun absDiff(a: Long, b: Long): Long =
        if (a >= b) a - b else b - a
}
