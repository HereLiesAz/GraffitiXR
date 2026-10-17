package com.hereliesaz.graffitixr.common.util

import com.hereliesaz.graffitixr.common.model.StencilView
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
    fun `all paints every tone and leaves cut-outs transparent`() {
        assertArrayEquals(intArrayOf(0, black, grey, white), StencilTones.render(ranks, StencilView.ALL))
    }

    @Test
    fun `each sheet paints only its own tone`() {
        assertArrayEquals(intArrayOf(0, black, 0, 0), StencilTones.render(ranks, StencilView.BLACK))
        assertArrayEquals(intArrayOf(0, 0, grey, 0), StencilTones.render(ranks, StencilView.GREY))
        assertArrayEquals(intArrayOf(0, 0, 0, white), StencilTones.render(ranks, StencilView.WHITE))
    }

    @Test
    fun `the cycler steps all, black, grey, white and wraps`() {
        assertEquals(StencilView.BLACK, StencilView.ALL.next())
        assertEquals(StencilView.GREY, StencilView.BLACK.next())
        assertEquals(StencilView.WHITE, StencilView.GREY.next())
        assertEquals(StencilView.ALL, StencilView.WHITE.next())
    }
}
