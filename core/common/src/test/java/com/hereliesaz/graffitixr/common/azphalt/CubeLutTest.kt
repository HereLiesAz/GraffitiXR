package com.hereliesaz.graffitixr.common.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CubeLutTest {

    private val identity = """
        TITLE "Identity"
        LUT_3D_SIZE 2
        0 0 0
        1 0 0
        0 1 0
        1 1 0
        0 0 1
        1 0 1
        0 1 1
        1 1 1
    """.trimIndent()

    // Each corner value = 1 - identity corner → a colour inversion.
    private val invert = """
        LUT_3D_SIZE 2
        1 1 1
        0 1 1
        1 0 1
        0 0 1
        1 1 0
        0 1 0
        1 0 0
        0 0 0
    """.trimIndent()

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `identity lut leaves pixels unchanged`() {
        val lut = parseCubeLut(identity)
        for (c in intArrayOf(0, 64, 128, 200, 255)) {
            val p = argb(255, c, c / 2, 255 - c)
            val out = lut.applyPixel(p)
            assertEquals("R", (p ushr 16) and 0xFF, (out ushr 16) and 0xFF)
            assertEquals("G", (p ushr 8) and 0xFF, (out ushr 8) and 0xFF)
            assertEquals("B", p and 0xFF, out and 0xFF)
        }
    }

    @Test
    fun `invert lut inverts colour and preserves alpha`() {
        val lut = parseCubeLut(invert)
        val out = lut.applyPixel(argb(0xAB, 30, 90, 200))
        assertEquals(0xAB, (out ushr 24) and 0xFF)
        assertEquals(255 - 30, (out ushr 16) and 0xFF)
        assertEquals(255 - 90, (out ushr 8) and 0xFF)
        assertEquals(255 - 200, out and 0xFF)
    }

    @Test
    fun `applyPixels grades a whole array`() {
        val lut = parseCubeLut(invert)
        val px = intArrayOf(argb(255, 0, 0, 0), argb(255, 255, 255, 255))
        lut.applyPixels(px)
        assertEquals(argb(255, 255, 255, 255), px[0])
        assertEquals(argb(255, 0, 0, 0), px[1])
    }

    @Test
    fun `comments and blank lines are ignored`() {
        val lut = parseCubeLut("# a comment\n\nLUT_3D_SIZE 2\n" + "0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1")
        assertEquals(2, lut.size)
    }

    @Test
    fun `1D lut is rejected`() {
        val e = assertThrows(IllegalArgumentException::class.java) { parseCubeLut("LUT_1D_SIZE 4\n0 0 0") }
        assertTrue(e.message!!.contains("1D"))
    }

    @Test
    fun `wrong value count is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { parseCubeLut("LUT_3D_SIZE 2\n0 0 0\n1 0 0") }
    }

    @Test
    fun `missing size is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { parseCubeLut("0 0 0\n1 1 1") }
    }
}
