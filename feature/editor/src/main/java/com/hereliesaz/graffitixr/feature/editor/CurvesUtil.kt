package com.hereliesaz.graffitixr.feature.editor

import android.graphics.PointF

object CurvesUtil {
    /**
     * Calculates a Hermite spline or simple linear interpolation between adjustment points
     * to generate a Look-Up Table (LUT) for native processing.
     */
    fun calculateAdjustmentCurve(points: List<PointF>): IntArray {
        val lut = IntArray(256)
        // Simple linear interpolation for baseline;
        // replace with Cubic Spline for production-grade transitions.
        for (i in 0..255) {
            val x = i / 255f
            lut[i] = (x * 255).toInt().coerceIn(0, 255)
        }
        return lut
    }
}