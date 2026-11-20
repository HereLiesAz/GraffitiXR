package com.hereliesaz.graffitixr.onboarding

import kotlin.math.abs

/**
 * The shape the first-run scribble should be drawn in: the artwork's. The scribble is shown after the
 * artwork is picked, and "make it big, roughly the size of your artwork" is the instruction, so a square
 * block of glyphs for a wide mural (or a tall banner) is the wrong thing to copy onto the wall.
 *
 * The shape is the bounding box of the picture's *subject*, not of the whole file: a cut-out on a
 * transparent background, or an object on a plain backdrop, is as wide and tall as the subject is. A
 * busy photo has no plain backdrop to cut away, so its box is the picture's own.
 *
 * Pure, so it is tested without Android; [scribbleAspectFor] feeds it a small decode of the picked image.
 */
object ScribbleFit {

    /** The aspect (width / height) is kept within this, so a sliver does not make an undrawable scribble. */
    const val MIN_ASPECT = 0.4f
    const val MAX_ASPECT = 2.5f

    /** Pixels less opaque than this count as background when the picture has transparency (0..255). */
    private const val OPAQUE_ALPHA = 250
    private const val SUBJECT_ALPHA = 32

    /** Share of the picture that must be see-through for it to count as a cut-out. */
    private const val CUTOUT_FRACTION = 0.02f

    /** Thickness of the frame, as a share of each side, whose colour is taken to be the backdrop. */
    private const val BORDER_FRACTION = 0.04f

    /** How far a pixel's channel must be from the backdrop's to count as subject (0..255). */
    private const val BACKDROP_DIFFERENCE = 40

    /** Less of the picture than this is subject: there is none to speak of, so use the whole picture. */
    private const val MIN_SUBJECT_FRACTION = 0.02f

    /** Share of the subject's pixels dropped from each end of each axis, so a stray speck cannot stretch the box. */
    private const val TRIM = 0.01f

    /**
     * The width / height of the subject in [argb] ([width] x [height], row-major ARGB), within
     * [[MIN_ASPECT], [MAX_ASPECT]]. The whole picture's aspect when it has no separable subject.
     */
    fun aspectOfSubject(argb: IntArray, width: Int, height: Int): Float {
        if (width <= 0 || height <= 0 || argb.size < width * height) return 1f
        val whole = (width.toFloat() / height).coerceIn(MIN_ASPECT, MAX_ASPECT)
        val n = width * height
        val mask = subjectMask(argb, width, height)
        var count = 0
        for (i in 0 until n) if (mask[i]) count++
        if (count < n * MIN_SUBJECT_FRACTION) return whole

        val cols = IntArray(width)
        val rows = IntArray(height)
        for (y in 0 until height) for (x in 0 until width) if (mask[y * width + x]) { cols[x]++; rows[y]++ }
        val (x0, x1) = trimmedExtent(cols, count)
        val (y0, y1) = trimmedExtent(rows, count)
        val bw = x1 - x0 + 1
        val bh = y1 - y0 + 1
        if (bw < 1 || bh < 1) return whole
        return (bw.toFloat() / bh).coerceIn(MIN_ASPECT, MAX_ASPECT)
    }

    /** The subject's pixels: the opaque ones of a cut-out, else those unlike the backdrop round the edge. */
    private fun subjectMask(argb: IntArray, width: Int, height: Int): BooleanArray {
        val n = width * height
        var seeThrough = 0
        for (i in 0 until n) if ((argb[i] ushr 24) < OPAQUE_ALPHA) seeThrough++
        val mask = BooleanArray(n)
        if (seeThrough >= n * CUTOUT_FRACTION) {
            for (i in 0 until n) mask[i] = (argb[i] ushr 24) > SUBJECT_ALPHA
            return mask
        }
        val bx = maxOf(1, (width * BORDER_FRACTION).toInt())
        val by = maxOf(1, (height * BORDER_FRACTION).toInt())
        val r = ArrayList<Int>(); val g = ArrayList<Int>(); val b = ArrayList<Int>()
        for (y in 0 until height) for (x in 0 until width) {
            if (x < bx || x >= width - bx || y < by || y >= height - by) {
                val c = argb[y * width + x]
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
        }
        val br = median(r); val bg = median(g); val bb = median(b)
        for (i in 0 until n) {
            val c = argb[i]
            mask[i] = maxOf(abs(((c shr 16) and 0xFF) - br), abs(((c shr 8) and 0xFF) - bg), abs((c and 0xFF) - bb)) > BACKDROP_DIFFERENCE
        }
        return mask
    }

    /** The first and last index of [counts] once [TRIM] of the [total] is dropped from each end. */
    private fun trimmedExtent(counts: IntArray, total: Int): Pair<Int, Int> {
        val cut = (total * TRIM).toInt()
        var lo = 0
        var seen = 0
        while (lo < counts.size - 1 && seen + counts[lo] <= cut) { seen += counts[lo]; lo++ }
        var hi = counts.size - 1
        seen = 0
        while (hi > lo && seen + counts[hi] <= cut) { seen += counts[hi]; hi-- }
        return lo to hi
    }

    private fun median(values: List<Int>): Int = if (values.isEmpty()) 0 else values.sorted()[values.size / 2]
}
