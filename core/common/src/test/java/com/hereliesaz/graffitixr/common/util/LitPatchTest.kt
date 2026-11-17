package com.hereliesaz.graffitixr.common.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Dark print on a lit patch in a dark scene (black text on a white flyer on a dark bar): the median
 * test in [isMarkPixel] loses it, [litPatchStrength] finds it by what it sits on. The other tests are
 * the ways such a rule goes wrong: a halo, the inside of a thick letter, the gaps between bright letters.
 */
class LitPatchTest {

    private val w = 640
    private val h = 480
    private val surface = 30

    private class Scene(val w: Int, val h: Int, bg: Int) {
        val luma = IntArray(w * h) { bg }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, v: Int) {
            for (y in y0 until y1) for (x in x0 until x1) luma[y * w + x] = v
        }
        operator fun get(x: Int, y: Int) = luma[y * w + x]
    }

    private fun integral(luma: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) {
            var sum = 0
            for (x in 0 until w) {
                sum += luma[y * w + x]
                out[y * w + x] = if (y == 0) sum else out[(y - 1) * w + x] + sum
            }
        }
        return out
    }

    private fun marks(s: Scene) = markMap(s.luma, integral(s.luma, s.w, s.h), s.w, s.h)

    private fun strength(s: Scene) = litPatchStrength(s.luma, s.w, s.h, medianLuma(s.luma), 25)

    /** A flyer: paper 170 on the surface, rows of dark print (70: lighter than the surface), in short words. */
    private fun flyer(): Pair<Scene, List<Int>> {
        val s = Scene(w, h, surface)
        s.rect(200, 100, 440, 380, 170)
        val text = ArrayList<Int>()
        for (row in 0 until 12) {
            val y = 130 + row * 20
            var x = 220
            while (x + 40 <= 420) {
                s.rect(x, y, x + 40, y + 4, 70)
                for (yy in y until y + 4) for (xx in x until x + 40) text += yy * w + xx
                x += 48
            }
        }
        return s to text
    }

    @Test
    fun `black print on a white flyer is a mark although it is lighter than the dark wall`() {
        val (s, text) = flyer()
        // The print is not darker than the surface by the median rule's margin, so the old rule drops it.
        assertTrue(70 >= medianLuma(s.luma) - 25)
        val m = marks(s)
        val found = text.count { m[it] }
        assertTrue("print found: $found of ${text.size}", found >= text.size * 0.9)
        // The paper's margin is not print.
        val st = strength(s)
        assertEquals(0, st[110 * w + 210].toInt())
        assertEquals(0, st[370 * w + 430].toInt())
    }

    @Test
    fun `the halo around a bright mark on a dark wall is not on a patch`() {
        val s = Scene(w, h, surface)
        s.rect(250, 150, 400, 300, 220)
        val st = strength(s)
        assertEquals(0, st.count { it.toInt() != 0 })
    }

    @Test
    fun `the inside of a thick painted letter is not print`() {
        val s = Scene(w, h, surface)
        s.rect(200, 100, 320, 220, 220)
        s.rect(230, 130, 290, 190, surface) // the counter
        val st = strength(s)
        assertEquals(0, st.count { it.toInt() != 0 })
    }

    @Test
    fun `the gaps between bright letters are not print`() {
        val s = Scene(w, h, surface)
        // A word: bright bars 4 px wide with 4 px gaps, 40 px tall.
        for (k in 0 until 15) s.rect(200 + k * 8, 150, 204 + k * 8, 190, 220)
        val st = strength(s)
        assertEquals(0, st.count { it.toInt() != 0 })
    }

    @Test
    fun `paper no lighter than the surface is not a patch`() {
        val s = Scene(w, h, 40)
        s.rect(200, 100, 440, 380, 60)
        for (row in 0 until 12) s.rect(220, 130 + row * 20, 260, 134 + row * 20, 20)
        assertEquals(0, strength(s).count { it.toInt() != 0 })
    }

    @Test
    fun `sensor noise on a dark frame is not a lit patch`() {
        val rnd = Random(11)
        // Uniform noise over 0..69 and bell-ish noise (sigma ~ 12): both lift to a "patch" when closed.
        val flat = Scene(w, h, 0).also { s -> for (i in s.luma.indices) s.luma[i] = rnd.nextInt(70) }
        assertEquals(0, strength(flat).count { it.toInt() != 0 })
        val bell = Scene(w, h, 0).also { s ->
            for (i in s.luma.indices) s.luma[i] = (40 + (0 until 6).sumOf { rnd.nextInt(-12, 13) } / 2).coerceIn(0, 255)
        }
        assertEquals(0, strength(bell).count { it.toInt() != 0 })
    }

    @Test
    fun `print on a flyer is still found with a little noise on the frame`() {
        val rnd = Random(5)
        val (s, text) = flyer()
        for (i in s.luma.indices) s.luma[i] = (s.luma[i] + rnd.nextInt(-5, 6)).coerceIn(0, 255)
        val m = marks(s)
        val found = text.count { m[it] }
        assertTrue("print found: $found of ${text.size}", found >= text.size * 0.9)
    }

    @Test
    fun `the noise estimate follows the noise`() {
        val rnd = Random(2)
        assertEquals(0.0, noiseSigma(IntArray(w * h) { 100 }, w, h), 0.0)
        val sigma5 = noiseSigma(IntArray(w * h) { 100 + rnd.nextInt(-9, 10) }, w, h) // uniform +-9: sigma 5.5
        assertTrue("sigma $sigma5", sigma5 in 4.0..7.0)
        // A flyer's print and edges barely move it.
        assertTrue(noiseSigma(flyer().first.luma, w, h) < 1.5)
    }

    @Test
    fun `a bright scene is not touched`() {
        // Light surface: the rule is not applied; the map equals the dark-print threshold alone.
        val s = Scene(w, h, 200)
        s.rect(200, 100, 440, 380, 230)
        for (row in 0 until 12) s.rect(220, 130 + row * 20, 260, 134 + row * 20, 90)
        assertTrue(!marksAreLight(s.luma))
        val m = marks(s)
        // Dark print is a mark there already (it is darker than the paper around it).
        assertTrue(m[132 * w + 230])
        // Nothing outside the print's neighbourhood and the paper's edge.
        assertTrue(!m[300 * w + 300])
    }

    @Test
    fun `sliding extrema match a brute force window`() {
        val rnd = Random(7)
        val sw = 23
        val sh = 17
        val src = ByteArray(sw * sh) { rnd.nextInt(256).toByte() }
        for (r in listOf(1, 3, 8)) for (max in listOf(true, false)) {
            val got = slide(src, sw, sh, r, max)
            for (y in 0 until sh) for (x in 0 until sw) {
                var best = if (max) -1 else 256
                for (yy in maxOf(0, y - r)..minOf(sh - 1, y + r)) for (xx in maxOf(0, x - r)..minOf(sw - 1, x + r)) {
                    val v = src[yy * sw + xx].toInt() and 0xFF
                    best = if (max) maxOf(best, v) else minOf(best, v)
                }
                assertEquals("r=$r max=$max ($x,$y)", best, got[y * sw + x].toInt() and 0xFF)
            }
        }
    }
}
