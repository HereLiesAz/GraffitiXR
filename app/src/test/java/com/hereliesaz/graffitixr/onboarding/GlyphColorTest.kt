package com.hereliesaz.graffitixr.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [GlyphColor]: the opposite of a backdrop is not its negative, because the opposite of muted is vivid. */
class GlyphColorTest {

    private fun hsv(rgb: Int) = FloatArray(3).also { GlyphColor.toHsv(rgb, it) }

    private fun glyphOver(rgb: Int, valueScale: Float = 1f): Int {
        val a = hsv(rgb)
        return GlyphColor.opposite(a[0], a[1], a[2] * valueScale)
    }

    @Test
    fun `hsv and rgb round trip`() {
        for (rgb in listOf(0x000000, 0xFFFFFF, 0x808080, 0xFF0000, 0x12AB56, 0xCC3366, 0x2F4F4F)) {
            val a = hsv(rgb)
            val back = GlyphColor.toRgb(a[0], a[1], a[2])
            for (shift in listOf(16, 8, 0)) {
                val d = Math.abs(((rgb shr shift) and 0xFF) - ((back shr shift) and 0xFF))
                assertTrue("$rgb ${back.toString(16)}", d <= 1)
            }
        }
    }

    @Test
    fun `the opposite of a grey is full saturation, not another grey`() {
        // The case the per-channel negative lost: 50% grey negates to 50% grey.
        val glyph = glyphOver(0x808080)
        val g = hsv(glyph)
        assertTrue("saturation ${g[1]}", g[1] >= 0.9f)
        assertTrue("separation ${GlyphColor.separation(glyph, 0x808080)}", GlyphColor.separation(glyph, 0x808080) >= GlyphColor.MIN_SEPARATION - 0.005f)
    }

    @Test
    fun `every grey, light to dark, gets a glyph that stands clear of it, vivid except at the extremes`() {
        for (v in 0..255 step 5) {
            val grey = (v shl 16) or (v shl 8) or v
            val glyph = glyphOver(grey)
            val gap = GlyphColor.separation(glyph, grey)
            assertTrue("grey $v separation $gap", gap >= GlyphColor.MIN_SEPARATION - 0.005f)
            // Over a mid-tone, where a negative is no use, the glyph is fully saturated.
            if (v in 60..200) assertTrue("grey $v saturation ${hsv(glyph)[1]}", hsv(glyph)[1] >= 0.95f)
        }
    }

    @Test
    fun `white is met with black and black with a bright vivid glyph`() {
        assertTrue((glyphOver(0xFFFFFF) and 0xFFFFFF) <= 0x0A0A0A)
        val onBlack = glyphOver(0x000000)
        val h = hsv(onBlack)
        assertTrue("sat ${h[1]}", h[1] >= 0.9f)
        assertTrue("value ${h[2]}", h[2] >= 0.9f)
        assertTrue("separation", GlyphColor.separation(onBlack, 0x000000) >= GlyphColor.MIN_SEPARATION)
    }

    @Test
    fun `a vivid backdrop is met with a neutral that stands clear of it`() {
        val glyph = glyphOver(0xFF0000)
        assertTrue("sat ${hsv(glyph)[1]}", hsv(glyph)[1] <= 0.15f)
        assertTrue("separation", GlyphColor.separation(glyph, 0xFF0000) >= GlyphColor.MIN_SEPARATION - 0.005f)
    }

    @Test
    fun `a coloured backdrop's hue turns half way round`() {
        val a = hsv(0x3A6EA5)             // a mid-saturated blue
        val b = hsv(GlyphColor.opposite(a[0], a[1], a[2]))
        val d = Math.abs(((b[0] - a[0] + 1.5f) % 1f) - 0.5f)   // distance round the wheel
        assertEquals("hue ${a[0]} -> ${b[0]}", 0.5f, d, 0.03f)
    }

    @Test
    fun `dimming the picture to nothing gives the vivid neutral glyph on black`() {
        // The slider takes the backdrop's value down towards black: whatever the picture, the glyph is then the
        // same vivid one a black backdrop gets.
        val a = glyphOver(0x3A6EA5, valueScale = 0f)
        val black = glyphOver(0x000000)
        assertEquals(black, a)
    }

    @Test
    fun `across the whole colour space the glyph stands clear of the backdrop`() {
        var total = 0
        var clear = 0
        var worst = 1f
        var h = 0f
        while (h < 1f) {
            var s = 0f
            while (s <= 1f) {
                var v = 0f
                while (v <= 1f) {
                    val back = GlyphColor.toRgb(h, s, v)
                    val glyph = GlyphColor.opposite(h, s, v)
                    val gap = GlyphColor.separation(glyph, back)
                    total++
                    if (gap >= GlyphColor.MIN_SEPARATION - 0.005f) clear++
                    worst = minOf(worst, gap)
                    assertTrue("rgb", glyph in 0..0xFFFFFF)
                    v += 0.05f
                }
                s += 0.1f
            }
            h += 0.05f
        }
        // Every backdrop gets a glyph at least the minimum gap away: the plain black or white is the last resort.
        assertTrue("worst gap $worst", worst >= GlyphColor.MIN_SEPARATION - 0.005f)
        assertTrue("only $clear of $total have a clear gap", clear == total)
    }
}
