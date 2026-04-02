// FILE: app/src/main/java/com/hereliesaz/graffitixr/common/util/ImageExt.kt
package com.hereliesaz.graffitixr.common.util

import android.graphics.Bitmap

/**
 * Deconstructs the visual reality of a poorly lit wall, stripping away the
 * chaotic noise of the background to isolate only the high-contrast markings.
 * Uses a Bradley-Roth adaptive threshold to outsmart uneven lighting.
 *
 * If [tapPos] is provided, the threshold becomes significantly more discerning
 * the further a pixel is from the tap location, ensuring only highly relevant
 * features are retained.
 */
fun Bitmap.isolateMarkings(tapPos: Pair<Float, Float>? = null): Bitmap {
    val w = this.width
    val h = this.height
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(w * h)
    this.getPixels(pixels, 0, w, 0, 0, w, h)

    val luma = IntArray(w * h)
    for (i in pixels.indices) {
        val c = pixels[i]
        luma[i] = (((c shr 16) and 0xFF) * 0.299 + ((c shr 8) and 0xFF) * 0.587 + ((c) and 0xFF) * 0.114).toInt()
    }

    val integral = IntArray(w * h)
    for (y in 0 until h) {
        var sum = 0
        for (x in 0 until w) {
            sum += luma[y * w + x]
            integral[y * w + x] = if (y == 0) sum else integral[(y - 1) * w + x] + sum
        }
    }

    val radius = w / 16
    val baseThresholdOffset = 25 // Increased from 15 for more discerning default

    val tapX = tapPos?.first?.let { it * w }
    val tapY = tapPos?.second?.let { it * h }
    val maxDist = Math.sqrt((w * w + h * h).toDouble()).toFloat()

    for (y in 0 until h) {
        for (x in 0 until w) {
            val x1 = maxOf(0, x - radius)
            val y1 = maxOf(0, y - radius)
            val x2 = minOf(w - 1, x + radius)
            val y2 = minOf(h - 1, y + radius)
            val count = (x2 - x1 + 1) * (y2 - y1 + 1)

            val a = if (x1 > 0 && y1 > 0) integral[(y1 - 1) * w + (x1 - 1)] else 0
            val b = if (y1 > 0) integral[(y1 - 1) * w + x2] else 0
            val c = if (x1 > 0) integral[y2 * w + (x1 - 1)] else 0
            val d = integral[y2 * w + x2]

            val sum = d - b - c + a
            val avg = sum / count

            // Distance-based discernment boost
            val distFactor = if (tapX != null && tapY != null) {
                val dx = x - tapX
                val dy = y - tapY
                val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                1f + (dist / maxDist) * 3f // Threshold offset increases up to 4x further away
            } else 1f

            val effectiveOffset = (baseThresholdOffset * distFactor).toInt()

            val i = y * w + x
            if (luma[i] < avg - effectiveOffset) {
                pixels[i] = pixels[i] or -0x1000000 // Opaque mark
            } else {
                pixels[i] = 0x00000000 // Transparent void
            }
        }
    }
    out.setPixels(pixels, 0, w, 0, 0, w, h)
    return out
}

/**
 * Executes a ruthless, non-recursive flood fill to eradicate a contiguous
 * blob of pixels from existence without inducing a StackOverflowError.
 */
fun Bitmap.eraseColorBlob(nx: Float, ny: Float): Bitmap {
    val w = this.width
    val h = this.height
    val x = (nx * w).toInt().coerceIn(0, w - 1)
    val y = (ny * h).toInt().coerceIn(0, h - 1)

    val out = this.copy(Bitmap.Config.ARGB_8888, true)
    val pixels = IntArray(w * h)
    out.getPixels(pixels, 0, w, 0, 0, w, h)

    val targetPixel = pixels[y * w + x]
    if (targetPixel == 0) return out // Tapped the void, do nothing

    // Primitive arrays instead of object allocations because efficiency is next to godliness
    val qx = IntArray(w * h)
    val qy = IntArray(w * h)
    var head = 0
    var tail = 0

    qx[tail] = x
    qy[tail] = y
    tail++
    pixels[y * w + x] = 0

    while (head < tail) {
        val cx = qx[head]
        val cy = qy[head]
        head++

        if (cx > 0 && pixels[cy * w + (cx - 1)] != 0) {
            pixels[cy * w + (cx - 1)] = 0
            qx[tail] = cx - 1
            qy[tail] = cy
            tail++
        }
        if (cx < w - 1 && pixels[cy * w + (cx + 1)] != 0) {
            pixels[cy * w + (cx + 1)] = 0
            qx[tail] = cx + 1
            qy[tail] = cy
            tail++
        }
        if (cy > 0 && pixels[(cy - 1) * w + cx] != 0) {
            pixels[(cy - 1) * w + cx] = 0
            qx[tail] = cx
            qy[tail] = cy - 1
            tail++
        }
        if (cy < h - 1 && pixels[(cy + 1) * w + cx] != 0) {
            pixels[(cy + 1) * w + cx] = 0
            qx[tail] = cx
            qy[tail] = cy + 1
            tail++
        }
    }

    out.setPixels(pixels, 0, w, 0, 0, w, h)
    return out
}