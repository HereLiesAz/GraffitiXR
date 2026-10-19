// FILE: app/src/main/java/com/hereliesaz/graffitixr/common/util/ImageExt.kt
package com.hereliesaz.graffitixr.common.util

import android.graphics.Bitmap

/**
 * Opaque colour every detected mark is painted with so the user can clearly SEE which marks
 * will feed the wall doodle. The marks are dark by definition (that is how they are
 * detected), so re-drawing them in their own colour would be near-invisible against a dark
 * wall — they must be highlighted. Only the ALPHA channel matters to the native doodle
 * masker (opaque = detect, transparent = skip), so the RGB highlight is purely visual.
 */
const val MARK_HIGHLIGHT_COLOR: Int = 0xFF00E5FF.toInt() // bright cyan

// Connected mark components smaller than max(this floor, area / NOISE_AREA_DIVISOR) pixels are
// treated as sensor/texture noise and dropped, so the user never sees meaningless "dots" — only
// whole marks survive. Raised the floor and tightened the area ratio (~4x) so small specks are
// never highlighted as potential markings in the first place; still keeps genuine thin strokes.
// On a ~2 MP capture the area term dominates: n/10000 ≈ 200 px.
private const val MIN_MARK_PIXELS_FLOOR = 48
private const val NOISE_AREA_DIVISOR = 10000

// How forgiving the erase touch is. A tap that misses every mark pixel snaps to the nearest mark
// within this fraction of the larger image dimension (floored at the px constant so it stays a
// usable target on small captures). Widening these makes mark removal less fiddly.
private const val ERASE_TOUCH_RADIUS_FRACTION = 0.05f
private const val ERASE_TOUCH_RADIUS_MIN_PX = 16

// 255 * width * height must stay under Int.MAX_VALUE (2_147_483_647) for isolateMarkings'
// IntArray-based integral image to not overflow: 2_147_483_647 / 255 ≈ 8_421_504.
private const val MAX_ISOLATE_MARKINGS_PIXELS = 8_000_000L

/**
 * Target-review work is intentionally capped near the ARCore capture size. CameraX still capture can
 * return 12+ MP JPEGs (for example 3024x4032 on Pixel 5); running mark isolation and descriptor
 * extraction at that resolution wastes memory/CPU and breaches the Int integral-image budget above.
 */
const val TARGET_REVIEW_MAX_PIXELS = 2_000_000L

internal fun fitWithinPixelBudget(
    width: Int,
    height: Int,
    maxPixels: Long = TARGET_REVIEW_MAX_PIXELS,
): Pair<Int, Int> {
    require(width > 0 && height > 0)
    require(maxPixels > 0)
    val pixels = width.toLong() * height.toLong()
    if (pixels <= maxPixels) return width to height

    val scale = kotlin.math.sqrt(maxPixels.toDouble() / pixels.toDouble())
    val scaledWidth = kotlin.math.floor(width * scale).toInt().coerceAtLeast(1)
    val scaledHeight = kotlin.math.floor(height * scale).toInt().coerceAtLeast(1)
    return scaledWidth to scaledHeight
}

/**
 * Returns an aspect-identical target-review bitmap that is safe for [isolateMarkings].
 *
 * The returned bitmap is the receiver itself when no resize is needed; callers must therefore only
 * recycle the original when the returned instance is different.
 */
fun Bitmap.forTargetReview(
    maxPixels: Long = TARGET_REVIEW_MAX_PIXELS,
): Bitmap {
    val (targetWidth, targetHeight) = fitWithinPixelBudget(width, height, maxPixels)
    if (targetWidth == width && targetHeight == height) return this
    return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
}

/**
 * Deconstructs the visual reality of a poorly lit wall, stripping away the
 * chaotic noise of the background to isolate only the high-contrast markings.
 * Uses a Bradley-Roth adaptive threshold to outsmart uneven lighting, then keeps only
 * connected mark blobs large enough to be a real mark (despeckle) and paints each whole mark
 * in [MARK_HIGHLIGHT_COLOR] so it is clearly visible — never a scatter of dots. A mark touching the
 * photo's edge is excluded: the frame cut it, so its shape there is the frame's, not the wall's.
 *
 * If [tapPos] is provided, the threshold becomes significantly more discerning
 * the further a pixel is from the tap location, ensuring only highly relevant
 * features are retained.
 */
fun Bitmap.isolateMarkings(tapPos: Pair<Float, Float>? = null): Bitmap {
    val w = this.width
    val h = this.height
    // The integral-image accumulator below is an IntArray: its running sum can reach
    // 255 * width * height, which overflows Int (and silently corrupts the threshold, with no
    // exception) above ~8.4 MP. Every current caller feeds this an ARCore capture (<= ~2 MP), so
    // this is a latent guard, not a live fix — but it's cheap and turns silent corruption into a
    // clear failure if a larger source is ever wired in.
    require(w.toLong() * h.toLong() <= MAX_ISOLATE_MARKINGS_PIXELS) {
        "isolateMarkings: image too large (${w}x$h) — integral-image accumulator would overflow Int"
    }
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

    // 2) Despeckle, drop marks cut by the frame, highlight the rest (see highlightWholeMarks).
    // Reuses the integral buffer as the flood queue: it is no longer needed after thresholding.
    val outPixels = highlightWholeMarks(isMark, w, h, maxOf(MIN_MARK_PIXELS_FLOOR, n / NOISE_AREA_DIVISOR), integral)
    out.setPixels(outPixels, 0, w, 0, 0, w, h)
    return out
}

/**
 * The highlight of [isMark] ([w] × [h]): each 8-connected mark of at least [minMarkPixels] pixels
 * that does not touch the image's edge, painted [MARK_HIGHLIGHT_COLOR] whole; everything else
 * transparent (0). A mark touching the edge is cut by the frame — the photo shows only part of it,
 * and the cut is the frame's line, not the wall's — so it is excluded before the artist sees it.
 * 8-connectivity keeps diagonal strokes whole (and matches [eraseColorBlob], so a tap removes
 * exactly one whole mark). [queue] (at least `w × h`) is scratch space.
 */
internal fun highlightWholeMarks(isMark: BooleanArray, w: Int, h: Int, minMarkPixels: Int, queue: IntArray = IntArray(w * h)): IntArray {
    val n = w * h
    val outPixels = IntArray(n) // zero == transparent void
    val visited = BooleanArray(n)
    // The queue stores packed (y shl 16 or x) coordinates so the inner loop avoids per-pixel
    // div/mod to recover x,y (image dims are always < 65536).
    var start = -1
    for (y in 0 until h) {
        for (x in 0 until w) {
            start++
            if (!isMark[start] || visited[start]) continue
            var head = 0
            var tail = 0
            var touchesEdge = false
            queue[tail++] = (y shl 16) or x
            visited[start] = true
            while (head < tail) {
                val p = queue[head++]
                val px = p and 0xFFFF
                val py = p ushr 16
                if (px == 0 || py == 0 || px == w - 1 || py == h - 1) touchesEdge = true
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
                                    queue[tail++] = (ny shl 16) or nx
                                }
                            }
                        }
                        dx++
                    }
                    dy++
                }
            }
            if (!touchesEdge && tail >= minMarkPixels) {
                for (k in 0 until tail) {
                    val p = queue[k]
                    outPixels[(p ushr 16) * w + (p and 0xFFFF)] = MARK_HIGHLIGHT_COLOR
                }
            }
        }
    }
    return outPixels
}

/**
 * Erases ONE contiguous mark from the highlight mask [pixels] ([w] × [h], ARGB; 0 == transparent)
 * in place, with a non-recursive 8-connected flood fill (no StackOverflowError), and returns whether
 * anything was erased. 8-connected so a single tap removes the WHOLE mark — including diagonal
 * strokes — matching the connectivity [isolateMarkings] uses to group marks.
 *
 * The touch isn't pixel-precise: if the tapped pixel is empty, the seed snaps to the NEAREST mark
 * pixel within a finger-sized radius so tapping (or dragging) close to a mark still removes it. A tap
 * with no mark anywhere in reach returns false having touched nothing.
 *
 * [queue] (at least `w × h`) is scratch space for the fill, passed in so a gesture's many samples
 * share one buffer instead of allocating per call; it holds `y * w + x` indices.
 */
internal fun eraseMarkAt(pixels: IntArray, w: Int, h: Int, nx: Float, ny: Float, queue: IntArray): Boolean {
    val x = (nx * w).toInt().coerceIn(0, w - 1)
    val y = (ny * h).toInt().coerceIn(0, h - 1)

    // Resolve the flood seed. If the tap landed dead-on a mark, use it; otherwise hunt the
    // closest mark pixel within ERASE_TOUCH_RADIUS_FRACTION of the larger image dimension
    // (with a sane floor) so a slightly-off touch still catches the mark the user aimed at.
    var seedX = x
    var seedY = y
    if (pixels[y * w + x] == 0) {
        val searchRadius = (maxOf(w, h) * ERASE_TOUCH_RADIUS_FRACTION).toInt().coerceAtLeast(ERASE_TOUCH_RADIUS_MIN_PX)
        val radiusSq = searchRadius * searchRadius
        val minX = (x - searchRadius).coerceAtLeast(0)
        val maxX = (x + searchRadius).coerceAtMost(w - 1)
        val minY = (y - searchRadius).coerceAtLeast(0)
        val maxY = (y + searchRadius).coerceAtMost(h - 1)
        var bestDistSq = Int.MAX_VALUE
        var found = false
        var sy = minY
        while (sy <= maxY) {
            val ddy = sy - y
            val ddySq = ddy * ddy
            // Whole row is already farther (vertically) than the best hit — skip it.
            if (ddySq >= bestDistSq) { sy++; continue }
            val rowBase = sy * w
            var sx = minX
            while (sx <= maxX) {
                if (pixels[rowBase + sx] != 0) {
                    val ddx = sx - x
                    val d = ddx * ddx + ddySq
                    if (d <= radiusSq && d < bestDistSq) {
                        bestDistSq = d
                        seedX = sx
                        seedY = sy
                        found = true
                    }
                }
                sx++
            }
            sy++
        }
        if (!found) return false // No mark within reach — a genuine void tap, do nothing
    }

    var head = 0
    var tail = 0
    queue[tail++] = seedY * w + seedX
    pixels[seedY * w + seedX] = 0

    while (head < tail) {
        val p = queue[head++]
        val cx = p % w
        val cy = p / w
        var dy = -1
        while (dy <= 1) {
            var dx = -1
            while (dx <= 1) {
                if (!(dx == 0 && dy == 0)) {
                    val ax = cx + dx
                    val ay = cy + dy
                    if (ax in 0 until w && ay in 0 until h && pixels[ay * w + ax] != 0) {
                        pixels[ay * w + ax] = 0
                        queue[tail++] = ay * w + ax
                    }
                }
                dx++
            }
            dy++
        }
    }
    return true
}

/**
 * The editable pixels of one mark-highlight mask, for a gesture of many erases.
 *
 * Decoding the mask ([Bitmap.getPixels]) and allocating the flood-fill queue are the two big costs
 * of an erase, and a drag fires dozens of erases a second. This holds both for the life of the
 * gesture: construct once from the bitmap on screen, [erase] as often as needed (each call touches
 * only the pixels of the mark it removes), then [publish] ONE new bitmap for the whole batch.
 * [publish] returns the new bitmap and retargets the eraser at it, so the next batch continues from it
 * with no re-decode; [isFor] says whether the eraser still describes the bitmap the UI shows (it does
 * not once the mask has been replaced by a new capture).
 *
 * Bitmaps are never mutated in place — Compose draws them by reference — and the superseded one is
 * deliberately not recycled here: the UI (and, after Confirm, the doodle job) may still hold it,
 * and recycling a bitmap that is being drawn crashes the draw. Dropping the reference is the safe
 * release. Not thread-safe; owned by one serialized caller.
 */
class MarkEraser(source: Bitmap) {
    private val w = source.width
    private val h = source.height
    private val pixels = IntArray(w * h).also { source.getPixels(it, 0, w, 0, 0, w, h) }
    private val queue = IntArray(w * h)
    private var current: Bitmap = source

    /** True while this eraser's pixels are the content of [bitmap]. */
    fun isFor(bitmap: Bitmap): Boolean = current === bitmap

    /** Erase the whole mark at ([nx], [ny]) (fractions of the image); false if none was in reach. */
    fun erase(nx: Float, ny: Float): Boolean = eraseMarkAt(pixels, w, h, nx, ny, queue)

    /** A new bitmap holding the erased state; this eraser now describes it. */
    fun publish(): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        current = out
        return out
    }
}

/**
 * Erases one whole mark at ([nx], [ny]) from this highlight mask — see [eraseMarkAt] for the touch
 * rules. Returns THIS bitmap, untouched and with no allocation of a bitmap, when no mark was in
 * reach (a genuine void tap costs one pixel decode and a bounded neighbourhood scan, not a copy);
 * otherwise a new bitmap, leaving the receiver unchanged. A caller erasing repeatedly (a drag) should
 * hold a [MarkEraser] instead, which also skips the per-call decode and queue allocation.
 */
fun Bitmap.eraseColorBlob(nx: Float, ny: Float): Bitmap {
    val eraser = MarkEraser(this)
    return if (eraser.erase(nx, ny)) eraser.publish() else this
}
