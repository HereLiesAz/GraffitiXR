package com.hereliesaz.graffitixr.common.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [eraseMarkAt]: one whole mark per touch, snapping to the nearest mark, reusable scratch. */
class EraseMarkAtTest {
    private val w = 60
    private val h = 40
    private val on = MARK_HIGHLIGHT_COLOR

    private fun mask(vararg rects: IntArray): IntArray = IntArray(w * h).also { m ->
        for (r in rects) for (y in r[1]..r[3]) for (x in r[0]..r[2]) m[y * w + x] = on
    }

    private fun erase(m: IntArray, px: Int, py: Int, queue: IntArray = IntArray(w * h)) =
        eraseMarkAt(m, w, h, (px + 0.5f) / w, (py + 0.5f) / h, queue)

    @Test fun `a touch on a mark removes that whole mark and only it`() {
        val m = mask(intArrayOf(5, 5, 14, 14), intArrayOf(30, 20, 39, 29))
        assertTrue(erase(m, 8, 8))
        assertEquals(100, m.count { it != 0 }) // the second 10x10 mark is intact
        assertEquals(0, m[8 * w + 8])
        assertEquals(on, m[25 * w + 35])
    }

    @Test fun `a diagonal stroke is removed whole`() {
        val m = IntArray(w * h)
        for (k in 0 until 20) m[(5 + k) * w + (10 + k)] = on
        assertTrue(erase(m, 10, 5))
        assertEquals(0, m.count { it != 0 })
    }

    @Test fun `an off-mark touch within reach snaps to the nearest mark`() {
        val m = mask(intArrayOf(20, 10, 24, 14))
        assertTrue(erase(m, 28, 12)) // 4 px right of the mark, inside the touch radius
        assertEquals(0, m.count { it != 0 })
    }

    @Test fun `a void touch erases nothing and reports it`() {
        val m = mask(intArrayOf(2, 2, 6, 6))
        val before = m.copyOf()
        assertFalse(erase(m, 55, 35))
        assertTrue(before.contentEquals(m))
    }

    @Test fun `re-touching an erased mark is a no-op`() {
        val m = mask(intArrayOf(5, 5, 14, 14))
        val q = IntArray(w * h)
        assertTrue(erase(m, 8, 8, q))
        assertFalse(erase(m, 8, 8, q))
    }

    @Test fun `the scratch queue is reusable across erases`() {
        val m = mask(intArrayOf(5, 5, 14, 14), intArrayOf(30, 20, 39, 29))
        val q = IntArray(w * h)
        assertTrue(erase(m, 8, 8, q))
        assertTrue(erase(m, 35, 25, q))
        assertEquals(0, m.count { it != 0 })
    }
}
