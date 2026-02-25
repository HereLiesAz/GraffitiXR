package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset

/**
 * Utility for generating color adjustment LUTs (Look Up Tables).
 * * NOTE: The CPU-bound `applyCurves` Bitmap loop has been intentionally removed.
 * Iterating through 12 million pixels sequentially on the CPU for every slider
 * micro-adjustment caused massive UI jank.
 * This LUT generation is now isolated, and the resulting array should be
 * passed to the MobileGS fragment shader or applied via RenderEffect for hardware acceleration.
 */

fun createLut(points: List<Offset>): IntArray {
    val lut = IntArray(256)
    val sortedPoints = points.sortedBy { it.x }

    for (i in 0..255) {
        val x = i / 255f
        val p1 = sortedPoints.lastOrNull { it.x <= x } ?: sortedPoints.first()
        val p2 = sortedPoints.firstOrNull { it.x >= x } ?: sortedPoints.last()

        val y = if (p1 == p2) {
            p1.y
        } else {
            val t = (x - p1.x) / (p2.x - p1.x)
            p1.y + t * (p2.y - p1.y)
        }
        lut[i] = (y * 255).toInt().coerceIn(0, 255)
    }

    return lut
}