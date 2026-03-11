// FILE: app/src/main/java/com/hereliesaz/graffitixr/common/util/ImageExt.kt
package com.hereliesaz.graffitixr.common.util

import android.graphics.Bitmap

/**
 * Deconstructs the visual reality of a poorly lit wall, stripping away the
 * chaotic noise of the background to isolate only the high-contrast markings.
 * Uses a Bradley-Roth adaptive threshold to outsmart uneven lighting.
 */
fun Bitmap.isolateMarkings(): Bitmap {
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
    val thresholdOffset = 15

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

            val i = y * w + x
            if (luma[i] < avg - thresholdOffset) {
                pixels[i] = pixels[i] or -0x1000000 // Opaque mark
            } else {
                pixels[i] = 0x00000000 // Transparent void
            }
        }
    }
    out.setPixels(pixels, 0, w, 0, 0, w, h)
    return out
}