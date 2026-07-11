package com.hereliesaz.graffitixr.common.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTuneTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `stats over a flat grey are that grey with zero contrast`() {
        val px = IntArray(100) { argb(255, 100, 100, 100) }
        val s = computeImageStats(px)
        assertEquals(100f, s.meanR, 0.5f)
        assertEquals(100f, s.luminance, 0.5f)
        assertEquals(0f, s.contrast, 0.5f)
    }

    @Test
    fun `transparent pixels are ignored`() {
        // Half opaque white, half transparent black — mean should reflect white only.
        val px = IntArray(100) { i -> if (i < 50) argb(255, 200, 200, 200) else argb(0, 0, 0, 0) }
        val s = computeImageStats(px)
        assertEquals(200f, s.meanR, 0.5f)
    }

    @Test
    fun `all-transparent yields neutral`() {
        val px = IntArray(50) { argb(0, 255, 255, 255) }
        assertEquals(ImageStats.NEUTRAL, computeImageStats(px))
    }

    @Test
    fun `contrast reflects luminance spread`() {
        // Half black, half white → high stddev.
        val px = IntArray(100) { i -> if (i < 50) argb(255, 0, 0, 0) else argb(255, 255, 255, 255) }
        val s = computeImageStats(px)
        assertTrue("contrast=${s.contrast}", s.contrast > 100f)
    }

    @Test
    fun `all outputs are within the documented clamps`() {
        val walls = listOf(
            ImageStats(20f, 20f, 20f, 20f, 5f),      // dark, flat
            ImageStats(240f, 240f, 240f, 240f, 3f),  // bright, flat
            ImageStats(180f, 90f, 60f, 110f, 70f),   // warm, busy
        )
        val arts = listOf(
            ImageStats(128f, 128f, 128f, 128f, 10f),
            ImageStats(200f, 50f, 50f, 90f, 55f),
        )
        for (w in walls) for (a in arts) {
            val r = computeAutoTune(w, a)
            assertTrue(r.opacity in 0.6f..0.9f)
            assertTrue(r.brightness in -0.2f..0.2f)
            assertTrue(r.contrast in 1f..1.25f)
            assertTrue(r.saturation in 1.0f..1.3f)
            assertTrue(r.colorBalanceR in 0.85f..1.15f)
            assertTrue(r.colorBalanceG in 0.85f..1.15f)
            assertTrue(r.colorBalanceB in 0.85f..1.15f)
        }
    }

    @Test
    fun `dark wall pulls bright art darker`() {
        val darkWall = ImageStats(20f, 20f, 20f, 20f, 5f)
        val brightArt = ImageStats(220f, 220f, 220f, 220f, 10f)
        assertTrue(computeAutoTune(darkWall, brightArt).brightness < 0f)
    }

    @Test
    fun `warm wall tints art warm - R up, B down`() {
        val warmWall = ImageStats(200f, 110f, 60f, 120f, 20f)
        val art = ImageStats(128f, 128f, 128f, 128f, 20f)
        val r = computeAutoTune(warmWall, art)
        assertTrue("cbR=${r.colorBalanceR}", r.colorBalanceR > 1f)
        assertTrue("cbB=${r.colorBalanceB}", r.colorBalanceB < 1f)
    }

    @Test
    fun `busy wall is more opaque than flat wall`() {
        val flat = ImageStats(128f, 128f, 128f, 128f, 2f)
        val busy = ImageStats(128f, 128f, 128f, 128f, 100f)
        val art = ImageStats(128f, 128f, 128f, 128f, 20f)
        assertTrue(computeAutoTune(busy, art).opacity < computeAutoTune(flat, art).opacity)
    }
}
