package com.hereliesaz.graffitixr.common.util

import android.graphics.Bitmap

/**
 * Android bridge for [computeImageStats]: downsamples to at most [sampleEdge] on the long side (stats
 * don't need full resolution and a 4K frame would allocate a 30MB IntArray) and reads the pixels.
 * Keeps [computeImageStats] itself Android-free and unit-testable.
 */
fun Bitmap.imageStats(sampleEdge: Int = 64): ImageStats {
    val longest = maxOf(width, height)
    val scaled = if (longest > sampleEdge) {
        val s = sampleEdge.toFloat() / longest
        Bitmap.createScaledBitmap(this, (width * s).toInt().coerceAtLeast(1), (height * s).toInt().coerceAtLeast(1), true)
    } else this
    val px = IntArray(scaled.width * scaled.height)
    scaled.getPixels(px, 0, scaled.width, 0, 0, scaled.width, scaled.height)
    if (scaled !== this) scaled.recycle()
    return computeImageStats(px)
}
