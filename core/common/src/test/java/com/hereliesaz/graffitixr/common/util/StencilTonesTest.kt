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
    fun `a dark image starts from a black base then grey then white`() {
        assertArrayEquals("black: the whole subject", intArrayOf(0, black, black, black),
            StencilTones.render(ranks, StencilView.BLACK, dark = true))
        assertArrayEquals("grey: grey and lighter", intArrayOf(0, 0, grey, grey),
            StencilTones.render(ranks, StencilView.GREY, dark = true))
        assertArrayEquals("white: the lightest", intArrayOf(0, 0, 0, white),
            StencilTones.render(ranks, StencilView.WHITE, dark = true))
    }

    @Test
    fun `a light image starts from a white base then grey then black`() {
        assertArrayEquals("white: the whole subject", intArrayOf(0, white, white, white),
            StencilTones.render(ranks, StencilView.WHITE, dark = false))
        assertArrayEquals("grey: grey and darker", intArrayOf(0, grey, grey, 0),
            StencilTones.render(ranks, StencilView.GREY, dark = false))
        assertArrayEquals("black: the darkest", intArrayOf(0, black, 0, 0),
            StencilTones.render(ranks, StencilView.BLACK, dark = false))
    }

    @Test
    fun `the cycler steps all, black, grey, white and wraps`() {
        assertEquals(StencilView.BLACK, StencilView.ALL.next())
        assertEquals(StencilView.GREY, StencilView.BLACK.next())
        assertEquals(StencilView.WHITE, StencilView.GREY.next())
        assertEquals(StencilView.ALL, StencilView.WHITE.next())
    }
}
