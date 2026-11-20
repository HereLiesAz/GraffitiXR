package com.hereliesaz.graffitixr.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** [ScribbleFit]: the shape of the picture's subject, which the first-run scribble is laid out to fit. */
class ScribbleFitTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val clear = 0x00000000

    private fun image(w: Int, h: Int, bg: Int) = IntArray(w * h) { bg }

    private fun IntArray.rect(w: Int, x0: Int, y0: Int, x1: Int, y1: Int, v: Int) {
        for (y in y0 until y1) for (x in x0 until x1) this[y * w + x] = v
    }

    @Test
    fun `a subject on a plain backdrop is as wide and tall as the subject, not the file`() {
        val w = 200; val h = 100
        val px = image(w, h, white).also { it.rect(w, 80, 10, 120, 90, black) } // 40 x 80
        assertEquals(0.5f, ScribbleFit.aspectOfSubject(px, w, h), 0.03f)
    }

    @Test
    fun `a cut-out on a transparent background is measured by its opaque pixels`() {
        val w = 300; val h = 200
        val px = image(w, h, clear).also { it.rect(w, 60, 70, 240, 130, 0xFF3366CC.toInt()) } // 180 x 60 = 3
        assertEquals(ScribbleFit.MAX_ASPECT, ScribbleFit.aspectOfSubject(px, w, h), 0f) // 3 clamps to 2.5
        val px2 = image(w, h, clear).also { it.rect(w, 100, 20, 200, 180, 0xFF3366CC.toInt()) } // 100 x 160
        assertEquals(0.625f, ScribbleFit.aspectOfSubject(px2, w, h), 0.03f)
    }

    @Test
    fun `a picture with no backdrop to cut away keeps its own shape`() {
        val w = 160; val h = 100
        val rnd = Random(3)
        val px = IntArray(w * h) { 0xFF000000.toInt() or (rnd.nextInt(256) shl 16) or (rnd.nextInt(256) shl 8) or rnd.nextInt(256) }
        assertEquals(1.6f, ScribbleFit.aspectOfSubject(px, w, h), 0.03f)
    }

    @Test
    fun `a blank picture falls back to its own shape`() {
        assertEquals(1.5f, ScribbleFit.aspectOfSubject(image(150, 100, white), 150, 100), 0.001f)
    }

    @Test
    fun `a few stray specks do not stretch the box`() {
        val w = 200; val h = 200
        val px = image(w, h, white).also {
            it.rect(w, 70, 60, 130, 140, black)  // 60 x 80 = 0.75
            it.rect(w, 5, 5, 7, 7, black)         // a speck in the corner
            it.rect(w, 190, 192, 192, 194, black) // and another
        }
        assertEquals(0.75f, ScribbleFit.aspectOfSubject(px, w, h), 0.05f)
    }

    @Test
    fun `the aspect is kept within a drawable range`() {
        val w = 400; val h = 100
        assertEquals(ScribbleFit.MAX_ASPECT, ScribbleFit.aspectOfSubject(image(w, h, white), w, h), 0f)
        val w2 = 100; val h2 = 400
        assertEquals(ScribbleFit.MIN_ASPECT, ScribbleFit.aspectOfSubject(image(w2, h2, white), w2, h2), 0f)
    }

    @Test
    fun `bad input is the square`() {
        assertEquals(1f, ScribbleFit.aspectOfSubject(IntArray(0), 0, 0), 0f)
        assertEquals(1f, ScribbleFit.aspectOfSubject(IntArray(5), 10, 10), 0f)
    }
}
