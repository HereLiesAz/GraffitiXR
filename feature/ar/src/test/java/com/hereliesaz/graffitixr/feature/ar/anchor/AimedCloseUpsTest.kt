package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [AimedCloseUps]: which deferred crops the camera's footprint on the photo overlaps. */
class AimedCloseUpsTest {

    private fun v(x: Int, y: Int, w: Int = 100, h: Int = 100) = CloseUpFeatures.View(x, y, w, h, 4f)
    private fun rect(x0: Float, y0: Float, x1: Float, y1: Float) = floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)

    @Test
    fun `a footprint inside one crop selects only that crop`() {
        val grid = listOf(v(0, 0), v(100, 0), v(0, 100), v(100, 100))
        assertEquals(listOf(grid[0]), AimedCloseUps.select(rect(10f, 10f, 60f, 60f), grid))
    }

    @Test
    fun `a footprint across a corner selects the crops it overlaps, most overlapped first`() {
        val grid = listOf(v(0, 0), v(100, 0), v(0, 100), v(100, 100))
        // 80 x 80 box over (60..140): 40x40 in the top-left, 40x40 each in the others -> equal; shift it to favour the first.
        val sel = AimedCloseUps.select(rect(40f, 40f, 140f, 140f), grid)
        assertEquals(4, sel.size)
        val skew = AimedCloseUps.select(rect(70f, 70f, 130f, 130f), grid)
        assertEquals(4, skew.size)
        val lean = AimedCloseUps.select(rect(20f, 20f, 120f, 120f), grid)
        assertEquals(grid[0], lean.first())
    }

    @Test
    fun `crops under the minimum share are left out`() {
        val grid = listOf(v(0, 0), v(100, 0))
        // 98 of 100 columns in the first crop, 2 in the second (2%): below 5%.
        assertEquals(listOf(grid[0]), AimedCloseUps.select(rect(2f, 10f, 102f, 60f), grid))
        assertEquals(2, AimedCloseUps.select(rect(2f, 10f, 102f, 60f), grid, minOverlap = 0.01f).size)
    }

    @Test
    fun `either winding and a tilted quad work`() {
        val grid = listOf(v(0, 0), v(100, 0))
        val cw = rect(10f, 10f, 60f, 60f)
        val ccw = floatArrayOf(cw[0], cw[1], cw[6], cw[7], cw[4], cw[5], cw[2], cw[3])
        assertEquals(AimedCloseUps.select(cw, grid), AimedCloseUps.select(ccw, grid))
        val diamond = floatArrayOf(50f, 10f, 90f, 50f, 50f, 90f, 10f, 50f)
        assertEquals(listOf(grid[0]), AimedCloseUps.select(diamond, grid))
    }

    @Test
    fun `a footprint off the photo, degenerate or not finite selects nothing`() {
        val grid = listOf(v(0, 0))
        assertTrue(AimedCloseUps.select(rect(500f, 500f, 600f, 600f), grid).isEmpty())
        assertTrue(AimedCloseUps.select(rect(10f, 10f, 10f, 60f), grid).isEmpty())
        assertTrue(AimedCloseUps.select(floatArrayOf(0f, 0f, Float.NaN, 0f, 1f, 1f, 0f, 1f), grid).isEmpty())
        assertTrue(AimedCloseUps.select(floatArrayOf(0f, 0f), grid).isEmpty())
    }

    @Test
    fun `ties keep the callers order`() {
        val grid = listOf(v(100, 0), v(0, 0))
        assertEquals(grid, AimedCloseUps.select(rect(50f, 10f, 150f, 60f), grid))
    }
}
