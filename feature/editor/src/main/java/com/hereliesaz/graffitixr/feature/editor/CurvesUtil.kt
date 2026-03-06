// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/CurvesUtil.kt
package com.hereliesaz.graffitixr.feature.editor

import android.graphics.PointF

object CurvesUtil {
    /**
     * Calculates a Cubic Spline interpolation between adjustment points
     * to generate a Look-Up Table (LUT) for native processing.
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
        val h = FloatArray(n - 1) { i -> x[i + 1] - x[i] }
        val alpha = FloatArray(n - 1) { i ->
            if (i == 0) 0f else (3f / h[i]) * (a[i + 1] - a[i]) - (3f / h[i - 1]) * (a[i] - a[i - 1])
        }

        val l = FloatArray(n)
        val mu = FloatArray(n)
        val z = FloatArray(n)
        l[0] = 1f; mu[0] = 0f; z[0] = 0f

        for (i in 1 until n - 1) {
            l[i] = 2f * (x[i + 1] - x[i - 1]) - h[i - 1] * mu[i - 1]
            mu[i] = h[i] / l[i]
            z[i] = (alpha[i] - h[i - 1] * z[i - 1]) / l[i]
        }

        l[n - 1] = 1f; z[n - 1] = 0f
        val c = FloatArray(n)
        val b = FloatArray(n)
        val d = FloatArray(n)

        for (j in n - 2 downTo 0) {
            c[j] = z[j] - mu[j] * c[j + 1]
            b[j] = (a[j + 1] - a[j]) / h[j] - h[j] * (c[j + 1] + 2f * c[j]) / 3f
            d[j] = (c[j + 1] - c[j]) / (3f * h[j])
        }

        for (i in 0..255) {
            val px = i / 255f
            var idx = x.binarySearch(px)
            if (idx < 0) idx = -idx - 2
            idx = idx.coerceIn(0, n - 2)

            val dx = px - x[idx]
            val py = a[idx] + b[idx] * dx + c[idx] * dx * dx + d[idx] * dx * dx * dx
            lut[i] = (py * 255).toInt().coerceIn(0, 255)
        }

        return lut
    }
}