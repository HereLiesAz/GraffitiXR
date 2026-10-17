// FILE: core/common/src/main/java/com/hereliesaz/graffitixr/common/util/StencilTones.kt
package com.hereliesaz.graffitixr.common.util

import com.hereliesaz.graffitixr.common.model.StencilView

/**
 * The parts of the stencil that need no OpenCV or Android, so they run (and are tested) on the JVM.
 */
object StencilTones {
    const val NONE = -1
    const val BLACK = 0
    const val GREY = 1
    const val WHITE = 2

    private const val ARGB_BLACK = 0xFF000000.toInt()
    private const val ARGB_GREY = 0xFF808080.toInt()
    private const val ARGB_WHITE = 0xFFFFFFFF.toInt()

    /**
     * Two thresholds splitting a 256-bin value histogram into three tones: 1-D K-means, K = 3,
     * started at the histogram's 1/6, 3/6, 5/6 quantiles, so the same image always gives the same
     * split. A value is black up to and including the first threshold, grey up to and including the
     * second, white above. Null when the histogram is empty.
     */
    fun thresholds(histogram: IntArray): Pair<Int, Int>? {
        require(histogram.size == 256) { "a value histogram has 256 bins" }
        val total = histogram.sum()
        if (total == 0) return null
        fun quantile(fraction: Double): Double {
            val target = fraction * total
            var seen = 0
            for (v in 0..255) {
                seen += histogram[v]
                if (seen >= target) return v.toDouble()
            }
            return 255.0
        }
        val centres = doubleArrayOf(quantile(1.0 / 6), quantile(3.0 / 6), quantile(5.0 / 6))
        repeat(50) {
            val sum = DoubleArray(3)
            val count = IntArray(3)
            for (v in 0..255) {
                val n = histogram[v]
                if (n == 0) continue
                var best = 0
                for (k in 1..2) if (kotlin.math.abs(v - centres[k]) < kotlin.math.abs(v - centres[best])) best = k
                sum[best] += v.toDouble() * n
                count[best] += n
            }
            var moved = false
            for (k in 0..2) {
                if (count[k] == 0) continue
                val next = sum[k] / count[k]
                if (kotlin.math.abs(next - centres[k]) > 1e-6) moved = true
                centres[k] = next
            }
            if (!moved) return@repeat
        }
        centres.sort()
        // Boundaries sit halfway between neighbouring centres; clamped so the thresholds stay ordered.
        val lo = ((centres[0] + centres[1]) / 2).toInt().coerceIn(0, 254)
        val hi = ((centres[1] + centres[2]) / 2).toInt().coerceIn(lo + 1, 255)
        return lo to hi
    }

    /**
     * ARGB pixels for [ranks] (one of [NONE], [BLACK], [GREY], [WHITE] per pixel) as [view] shows
     * them: ALL paints every tone; a single sheet paints only its own tone and leaves the rest
     * transparent. [NONE] is always transparent.
     */
    fun render(ranks: IntArray, view: StencilView): IntArray = IntArray(ranks.size) { i ->
        val rank = ranks[i]
        val shown = when (view) {
            StencilView.ALL -> rank != NONE
            StencilView.BLACK -> rank == BLACK
            StencilView.GREY -> rank == GREY
            StencilView.WHITE -> rank == WHITE
        }
        if (!shown) 0 else when (rank) {
            BLACK -> ARGB_BLACK
            GREY -> ARGB_GREY
            else -> ARGB_WHITE
        }
    }
}
