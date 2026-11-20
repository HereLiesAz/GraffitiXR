package com.hereliesaz.graffitixr.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // --- the region the scribble is laid over ---

    private fun region(px: IntArray, w: Int, h: Int) = ScribbleFit.subjectOf(px, w, h)

    @Test
    fun `the region is the subject's box, as fractions of the picture`() {
        val w = 200; val h = 100
        val px = image(w, h, white).also { it.rect(w, 80, 10, 120, 90, black) } // x 80..120, y 10..90
        val r = region(px, w, h).region
        assertEquals(0.4f, r.left, 0.02f)
        assertEquals(0.6f, r.right, 0.02f)
        assertEquals(0.1f, r.top, 0.02f)
        assertEquals(0.9f, r.bottom, 0.02f)
    }

    @Test
    fun `a blank picture's region is all of it`() {
        val r = region(image(120, 80, white), 120, 80).region
        assertEquals(ScribbleFit.Region(0f, 0f, 1f, 1f), r)
    }

    @Test
    fun `a subject too wide to draw is grown taller about its centre, inside the picture`() {
        val w = 300; val h = 200
        val px = image(w, h, clear).also { it.rect(w, 60, 90, 240, 110, 0xFF3366CC.toInt()) } // 180 x 20: 9:1
        val s = region(px, w, h)
        assertEquals(ScribbleFit.MAX_ASPECT, s.aspect, 1e-4f)
        // The region's own aspect, in pixels, is the aspect it reports.
        val rw = (s.region.right - s.region.left) * w
        val rh = (s.region.bottom - s.region.top) * h
        assertEquals(s.aspect, rw / rh, 1e-3f)
        // Still centred on the subject (y 100 of 200) and inside the picture.
        assertEquals(0.5f, (s.region.top + s.region.bottom) / 2f, 0.02f)
        assertTrue(s.region.left >= 0f && s.region.right <= 1f && s.region.top >= 0f && s.region.bottom <= 1f)
    }

    @Test
    fun `a region that would leave the picture is moved back inside it`() {
        val w = 200; val h = 200
        // A thin subject hard against the left edge: growing it wider must not push it out of the picture.
        val px = image(w, h, clear).also { it.rect(w, 0, 20, 10, 180, 0xFF3366CC.toInt()) } // 10 x 160
        val s = region(px, w, h)
        assertEquals(ScribbleFit.MIN_ASPECT, s.aspect, 1e-4f)
        assertTrue(s.region.left >= 0f && s.region.right <= 1f && s.region.top >= 0f && s.region.bottom <= 1f)
    }

    @Test
    fun `every region lies inside the picture and reports its own aspect`() {
        val rnd = Random(9)
        repeat(300) {
            val w = rnd.nextInt(20, 300); val h = rnd.nextInt(20, 300)
            val px = image(w, h, white)
            val x0 = rnd.nextInt(0, w - 4); val y0 = rnd.nextInt(0, h - 4)
            px.rect(w, x0, y0, rnd.nextInt(x0 + 3, w + 1), rnd.nextInt(y0 + 3, h + 1), black)
            val s = region(px, w, h)
            val r = s.region
            assertTrue("$w x $h $r", r.left >= -1e-4f && r.top >= -1e-4f && r.right <= 1f + 1e-4f && r.bottom <= 1f + 1e-4f)
            assertTrue("$w x $h $r", r.right > r.left && r.bottom > r.top)
            assertTrue("aspect ${s.aspect}", s.aspect in ScribbleFit.MIN_ASPECT..ScribbleFit.MAX_ASPECT)
            assertEquals("$w x $h $r", s.aspect, (r.right - r.left) * w / ((r.bottom - r.top) * h), 0.02f)
        }
    }

    // --- the silhouette ---

    private fun disc(w: Int, h: Int, cx: Int, cy: Int, r: Int, bg: Int = white, fg: Int = black): IntArray {
        val px = image(w, h, bg)
        for (y in 0 until h) for (x in 0 until w) if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) px[y * w + x] = fg
        return px
    }

    @Test
    fun `a round subject gets a round silhouette, empty in the corners`() {
        val m = ScribbleFit.subjectOf(disc(96, 96, 48, 48, 40), 96, 96).mask!!
        assertTrue(m[m.width / 2, m.height / 2])
        assertTrue(!m[0, 0] && !m[m.width - 1, 0] && !m[0, m.height - 1] && !m[m.width - 1, m.height - 1])
        val filled = m.bits.count { it }.toFloat() / m.bits.size
        assertTrue("filled $filled", filled in 0.6f..0.9f) // pi/4 of a square, about 0.785
    }

    @Test
    fun `the silhouette's cells keep the region's proportions`() {
        val px = image(96, 96, white).also { it.rect(96, 8, 30, 88, 66, black); it.rect(96, 20, 20, 40, 76, black) } // a plus-ish shape, wider than tall
        val s = ScribbleFit.subjectOf(px, 96, 96)
        val m = s.mask!!
        assertEquals(s.aspect, m.width.toFloat() / m.height, 0.08f)
    }

    @Test
    fun `a subject that fills its box has no silhouette to hold glyphs to`() {
        val px = image(96, 96, white).also { it.rect(96, 10, 20, 80, 70, black) }
        assertEquals(null, ScribbleFit.subjectOf(px, 96, 96).mask)
    }

    @Test
    fun `a small hole inside the subject is filled, not avoided`() {
        val px = disc(96, 96, 48, 48, 40)
        for (y in 44..52) for (x in 44..52) px[y * 96 + x] = white // a pupil
        val m = ScribbleFit.subjectOf(px, 96, 96).mask!!
        assertTrue(m[m.width / 2, m.height / 2])
    }

    @Test
    fun `a big gap, as in a ring, stays a gap`() {
        val px = disc(96, 96, 48, 48, 44)
        for (y in 0 until 96) for (x in 0 until 96) if ((x - 48) * (x - 48) + (y - 48) * (y - 48) <= 24 * 24) px[y * 96 + x] = white
        val m = ScribbleFit.subjectOf(px, 96, 96).mask!!
        assertTrue(!m[m.width / 2, m.height / 2])
    }

    @Test
    fun `a stray speck away from the subject is left out of the silhouette`() {
        val px = disc(96, 96, 30, 48, 24)
        for (y in 4..8) for (x in 88..92) px[y * 96 + x] = black
        val s = ScribbleFit.subjectOf(px, 96, 96)
        val m = s.mask!!
        assertTrue("far corner must stay empty", !m[m.width - 1, 0])
    }

    @Test
    fun `a cut-out's silhouette follows its transparent surround`() {
        val px = disc(96, 96, 48, 48, 40, bg = clear, fg = black)
        val m = ScribbleFit.subjectOf(px, 96, 96).mask!!
        assertTrue(m[m.width / 2, m.height / 2] && !m[0, 0])
    }
}
