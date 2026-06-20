// FILE: app/src/main/java/com/hereliesaz/graffitixr/common/util/ImageExt.kt
package com.hereliesaz.graffitixr.common.util

import android.graphics.Bitmap

/**
 * Opaque colour every detected mark is painted with so the user can clearly SEE which marks
 * will feed the wall fingerprint. The marks are dark by definition (that is how they are
 * detected), so re-drawing them in their own colour would be near-invisible against a dark
 * wall — they must be highlighted. Only the ALPHA channel matters to the native fingerprint
 * masker (opaque = detect, transparent = skip), so the RGB highlight is purely visual.
 */
const val MARK_HIGHLIGHT_COLOR: Int = 0xFF00E5FF.toInt() // bright cyan

// Connected mark components smaller than max(this floor, area / NOISE_AREA_DIVISOR) pixels are
// treated as sensor/texture noise and dropped, so the user never sees meaningless "dots" — only
// whole marks survive. Tunable; deliberately conservative so thin real strokes are kept.
private const val MIN_MARK_PIXELS_FLOOR = 12
private const val NOISE_AREA_DIVISOR = 40000

/**
 * Deconstructs the visual reality of a poorly lit wall, stripping away the
 * chaotic noise of the background to isolate only the high-contrast markings.
 * Uses a Bradley-Roth adaptive threshold to outsmart uneven lighting, then keeps only
 * connected mark blobs large enough to be a real mark (despeckle) and paints each whole mark
 * in [MARK_HIGHLIGHT_COLOR] so it is clearly visible — never a scatter of dots.
 *
 * If [tapPos] is provided, the threshold becomes significantly more discerning
 * the further a pixel is from the tap location, ensuring only highly relevant
 * features are retained.
 */
fun Bitmap.isolateMarkings(tapPos: Pair<Float, Float>? = null): Bitmap {
    val w = this.width
    val h = this.height
    val n = w * h
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(n)
    this.getPixels(pixels, 0, w, 0, 0, w, h)

    val luma = IntArray(n)
    for (i in pixels.indices) {
        val c = pixels[i]
        luma[i] = (((c shr 16) and 0xFF) * 0.299 + ((c shr 8) and 0xFF) * 0.587 + ((c) and 0xFF) * 0.114).toInt()
    }

    val integral = IntArray(n)
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

    // 1) Adaptive threshold → binary mark map.
    val isMark = BooleanArray(n)
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

            val avg = (d - b - c + a) / count

            // Distance-based discernment boost
            val distFactor = if (tapX != null && tapY != null) {
                val dx = x - tapX
                val dy = y - tapY
                val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                1f + (dist / maxDist) * 3f // Threshold offset increases up to 4x further away
            } else 1f

            val effectiveOffset = (baseThresholdOffset * distFactor).toInt()

            val i = y * w + x
            isMark[i] = luma[i] < avg - effectiveOffset
        }
    }

    // 2) Despeckle + highlight: keep only 8-connected blobs big enough to be a real mark, and
    //    paint each whole surviving mark with the highlight colour. 8-connectivity keeps diagonal
    //    strokes whole (and matches eraseColorBlob, so a tap removes exactly one whole mark).
    val minMarkPixels = maxOf(MIN_MARK_PIXELS_FLOOR, n / NOISE_AREA_DIVISOR)
    val outPixels = IntArray(n) // zero == transparent void
    val visited = BooleanArray(n)
    val queue = integral // reuse: the integral image is no longer needed
    for (start in 0 until n) {
        if (!isMark[start] || visited[start]) continue
        var head = 0
        var tail = 0
        queue[tail++] = start
        visited[start] = true
        while (head < tail) {
            val p = queue[head++]
            val px = p % w
            val py = p / w
            var dy = -1
            while (dy <= 1) {
                var dx = -1
                while (dx <= 1) {
                    if (!(dx == 0 && dy == 0)) {
                        val nx = px + dx
                        val ny = py + dy
                        if (nx in 0 until w && ny in 0 until h) {
                            val q = ny * w + nx
                            if (isMark[q] && !visited[q]) {
                                visited[q] = true
                                queue[tail++] = q
                            }
                        }
                    }
                    dx++
                }
                dy++
            }
        }
        if (tail >= minMarkPixels) {
            for (k in 0 until tail) outPixels[queue[k]] = MARK_HIGHLIGHT_COLOR
        }
    }
    out.setPixels(outPixels, 0, w, 0, 0, w, h)
    return out
}

/**
 * Executes a ruthless, non-recursive flood fill to eradicate one contiguous mark from
 * existence (clearing it to transparent) without inducing a StackOverflowError. 8-connected
 * so a single tap removes the WHOLE mark — including diagonal strokes — matching the
 * connectivity [isolateMarkings] uses to group marks. Tapping the void is a no-op.
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

        var dy = -1
        while (dy <= 1) {
            var dx = -1
            while (dx <= 1) {
                if (!(dx == 0 && dy == 0)) {
                    val ax = cx + dx
                    val ay = cy + dy
                    if (ax in 0 until w && ay in 0 until h && pixels[ay * w + ax] != 0) {
                        pixels[ay * w + ax] = 0
                        qx[tail] = ax
                        qy[tail] = ay
                        tail++
                    }
                }
                dx++
            }
            dy++
        }
    }

    out.setPixels(pixels, 0, w, 0, 0, w, h)
    return out
}