// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/CurvesUtil.kt
package com.hereliesaz.graffitixr.feature.editor

import android.graphics.PointF

object CurvesUtil {
    /**
     * Calculates a Monotone Cubic Spline interpolation between adjustment points.
     * Replaces jagged linear transitions with actual mathematical gradients.
     */
    fun calculateAdjustmentCurve(points: List<PointF>): IntArray {
        val lut = IntArray(256)
        val n = points.size

        if (n < 2) {
            val defaultVal = (points.firstOrNull()?.y?.times(255))?.toInt()?.coerceIn(0, 255) ?: 0
            for (i in 0..255) lut[i] = defaultVal
            return lut
        }

        val x = points.map { it.x }.toFloatArray()
        val a = points.map { it.y }.toFloatArray()
        val h = FloatArray(n - 1) { i ->
            val diff = x[i + 1] - x[i]
            if (diff <= 0f) 1e-5f else diff
        }
        // Secant slopes between consecutive control points.
        val delta = FloatArray(n - 1) { i -> (a[i + 1] - a[i]) / h[i] }

        // Fritsch–Carlson tangents: start from averaged secants, zero them at local extrema, then
        // clamp each so no segment overshoots. This clamping is what makes the interpolation actually
        // monotone — a plain natural cubic can bulge past the control points and invert tones.
        val m = FloatArray(n)
        m[0] = delta[0]
        m[n - 1] = delta[n - 2]
        for (i in 1 until n - 1) {
            m[i] = if (delta[i - 1] * delta[i] <= 0f) 0f else (delta[i - 1] + delta[i]) / 2f
        }
        for (i in 0 until n - 1) {
            if (delta[i] == 0f) {
                m[i] = 0f; m[i + 1] = 0f
            } else {
                val alpha = m[i] / delta[i]
                val beta = m[i + 1] / delta[i]
                val s = alpha * alpha + beta * beta
                if (s > 9f) {
                    val tau = 3f / kotlin.math.sqrt(s)
                    m[i] = tau * alpha * delta[i]
                    m[i + 1] = tau * beta * delta[i]
                }
            }
        }

        for (i in 0..255) {
            val px = i / 255f
            var idx = x.binarySearch(px)
            if (idx < 0) idx = -idx - 2
            idx = idx.coerceIn(0, n - 2)

            // Cubic Hermite basis on the normalized segment parameter t ∈ [0,1].
            val t = ((px - x[idx]) / h[idx]).coerceIn(0f, 1f)
            val t2 = t * t
            val t3 = t2 * t
            val h00 = 2f * t3 - 3f * t2 + 1f
            val h10 = t3 - 2f * t2 + t
            val h01 = -2f * t3 + 3f * t2
            val h11 = t3 - t2
            val py = h00 * a[idx] + h10 * h[idx] * m[idx] + h01 * a[idx + 1] + h11 * h[idx] * m[idx + 1]
            lut[i] = (py * 255).toInt().coerceIn(0, 255)
        }

        return lut
    }
}