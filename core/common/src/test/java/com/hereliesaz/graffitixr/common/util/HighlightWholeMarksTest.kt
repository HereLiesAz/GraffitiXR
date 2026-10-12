package com.hereliesaz.graffitixr.common.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [highlightWholeMarks]: whole marks only, none cut by the photo's edge. */
class HighlightWholeMarksTest {

    private val w = 40
    private val h = 30

    private fun marks(vararg rects: IntArray): BooleanArray = BooleanArray(w * h).also { m ->
        for ((x0, y0, x1, y1) in rects.map { it.toList() }) for (y in y0..y1) for (x in x0..x1) m[y * w + x] = true
    }

    private fun highlighted(out: IntArray, x: Int, y: Int) = out[y * w + x] != 0

    @Test
    fun `a mark touching any edge is excluded whole, an inner one kept whole`() {
        val m = marks(
            intArrayOf(10, 10, 19, 19), // inside
            intArrayOf(0, 2, 5, 8),     // left edge
            intArrayOf(30, 0, 35, 5),   // top edge
            intArrayOf(34, 12, 39, 18), // right edge
            intArrayOf(2, 24, 8, 29),   // bottom edge
        )
        val out = highlightWholeMarks(m, w, h, minMarkPixels = 4)
        for (y in 10..19) for (x in 10..19) assertTrue(highlighted(out, x, y))
        // Nothing else is highlighted: every edge mark is gone, all of it.
        assertEquals(100, out.count { it != 0 })
        assertTrue(out.filter { it != 0 }.all { it == MARK_HIGHLIGHT_COLOR })
    }

    @Test
    fun `a mark reaching the edge through a diagonal stroke is excluded too`() {
        val m = marks(intArrayOf(10, 10, 15, 15))
        // A diagonal tail from the mark's corner to the left edge (8-connected).
        for (k in 1..10) m[(10 - k) * w + (10 - k)] = true
        assertEquals(0, highlightWholeMarks(m, w, h, minMarkPixels = 4).count { it != 0 })
    }

    @Test
    fun `specks stay despeckled`() {
        val m = marks(intArrayOf(10, 10, 11, 11), intArrayOf(20, 10, 29, 19))
        val out = highlightWholeMarks(m, w, h, minMarkPixels = 10)
        assertTrue(!highlighted(out, 10, 10))
        assertTrue(highlighted(out, 25, 15))
    }
}
