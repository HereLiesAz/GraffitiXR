package com.hereliesaz.graffitixr.common.util

import com.hereliesaz.graffitixr.common.model.StencilView
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StencilTonesTest {

    private fun histogramOf(vararg values: Pair<Int, Int>): IntArray {
        val h = IntArray(256)
        for ((v, n) in values) h[v] += n
        return h
    }

    @Test
    fun `three clear tones split between their centres`() {
        val (lo, hi) = StencilTones.thresholds(histogramOf(20 to 100, 128 to 100, 235 to 100))!!
        assertTrue("first threshold $lo should fall between the dark and middle tones", lo in 20..127)
        assertTrue("second threshold $hi should fall between the middle and light tones", hi in 128..234)
    }

    @Test
    fun `thresholds are ordered even for a one-tone image`() {
        val (lo, hi) = StencilTones.thresholds(histogramOf(90 to 500))!!
        assertTrue("thresholds must be ordered, got $lo, $hi", lo < hi)
    }

    @Test
    fun `an empty histogram has no thresholds`() {
        assertNull(StencilTones.thresholds(IntArray(256)))
    }

    @Test
    fun `the same histogram always splits the same way`() {
        val h = histogramOf(10 to 40, 60 to 70, 140 to 90, 200 to 30, 250 to 60)
        assertEquals(StencilTones.thresholds(h), StencilTones.thresholds(h))
    }

    private val ranks = intArrayOf(StencilTones.NONE, StencilTones.BLACK, StencilTones.GREY, StencilTones.WHITE)
    private val black = 0xFF000000.toInt()
    private val grey = 0xFF808080.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun `a dark image is judged dark and a light one light`() {
        assertTrue(StencilTones.isDark(0.5))
        assertTrue(StencilTones.isDark(0.1))
        assertFalse(StencilTones.isDark(0.51))
        assertEquals(0.0, StencilTones.luma(0, 0, 0), 1e-9)
        assertEquals(1.0, StencilTones.luma(255, 255, 255), 1e-9)
        assertTrue("green outweighs blue", StencilTones.luma(0, 255, 0) > StencilTones.luma(0, 0, 255))
    }

    @Test
    fun `all stacks every tone and leaves cut-outs transparent either way`() {
        val expected = intArrayOf(0, black, grey, white)
        assertArrayEquals(expected, StencilTones.render(ranks, StencilView.ALL, dark = true))
        assertArrayEquals(expected, StencilTones.render(ranks, StencilView.ALL, dark = false))
    }

    @Test
    fun `a dark image goes black base, grey midtone, white secondary`() {
        assertArrayEquals("base: the whole subject", intArrayOf(0, black, black, black),
            StencilTones.render(ranks, StencilView.BASE, dark = true))
        assertArrayEquals("midtone: grey and lighter", intArrayOf(0, 0, grey, grey),
            StencilTones.render(ranks, StencilView.MIDTONE, dark = true))
        assertArrayEquals("secondary: the lightest", intArrayOf(0, 0, 0, white),
            StencilTones.render(ranks, StencilView.SECONDARY, dark = true))
        assertEquals(listOf(StencilTones.BLACK, StencilTones.GREY, StencilTones.WHITE), StencilTones.sheetOrder(true))
    }

    @Test
    fun `a light image goes white base, grey midtone, black secondary`() {
        assertArrayEquals("base: the whole subject", intArrayOf(0, white, white, white),
            StencilTones.render(ranks, StencilView.BASE, dark = false))
        assertArrayEquals("midtone: grey and darker", intArrayOf(0, grey, grey, 0),
            StencilTones.render(ranks, StencilView.MIDTONE, dark = false))
        assertArrayEquals("secondary: the darkest", intArrayOf(0, black, 0, 0),
            StencilTones.render(ranks, StencilView.SECONDARY, dark = false))
        assertEquals(listOf(StencilTones.WHITE, StencilTones.GREY, StencilTones.BLACK), StencilTones.sheetOrder(false))
    }

    private fun flags(vararg v: Int) = BooleanArray(v.size) { v[it] != 0 }

    @Test
    fun `the base is the complete silhouette, holes included, and nothing outside it`() {
        // pixel 0 outside, 1 a hole in the cut-out (not observed, inside the silhouette), 2 and 3 subject
        val silhouette = flags(0, 1, 1, 1)
        val observed = flags(0, 0, 1, 1)
        val none = flags(0, 0, 0, 0)
        for (dark in listOf(true, false)) {
            val r = StencilTones.ranks(silhouette, observed, none, none, dark)
            val base = if (dark) StencilTones.BLACK else StencilTones.WHITE
            assertArrayEquals(intArrayOf(StencilTones.NONE, base, base, base), r)
            // The base sheet shows exactly the silhouette.
            val shown = StencilTones.render(r, StencilView.BASE, dark).map { it != 0 }
            assertEquals(listOf(false, true, true, true), shown)
        }
    }

    @Test
    fun `an uncut image has the whole frame as its silhouette`() {
        val all = flags(1, 1, 1, 1)
        val r = StencilTones.ranks(all, all, flags(0, 0, 1, 1), flags(0, 0, 0, 1), dark = true)
        assertTrue(r.all { it != StencilTones.NONE })
    }

    @Test
    fun `the midtone covers at least as much as the secondary, even if its own mask says otherwise`() {
        // The secondary mask has a pixel (index 3) the midtone mask lacks.
        val all = flags(1, 1, 1, 1)
        val middle = flags(0, 1, 1, 0)
        val top = flags(0, 0, 1, 1)
        for (dark in listOf(true, false)) {
            val r = StencilTones.ranks(all, all, middle, top, dark)
            val mid = StencilTones.render(r, StencilView.MIDTONE, dark).map { it != 0 }
            val sec = StencilTones.render(r, StencilView.SECONDARY, dark).map { it != 0 }
            for (i in sec.indices) assertTrue("pixel $i is secondary but not midtone (dark=$dark)", !sec[i] || mid[i])
            assertTrue(mid.count { it } >= sec.count { it })
        }
    }

    @Test
    fun `the cycler steps all, base, midtone, secondary and wraps`() {
        assertEquals(StencilView.BASE, StencilView.ALL.next())
        assertEquals(StencilView.MIDTONE, StencilView.BASE.next())
        assertEquals(StencilView.SECONDARY, StencilView.MIDTONE.next())
        assertEquals(StencilView.ALL, StencilView.SECONDARY.next())
    }
}
