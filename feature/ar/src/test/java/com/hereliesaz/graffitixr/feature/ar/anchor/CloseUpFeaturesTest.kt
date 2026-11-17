package com.hereliesaz.graffitixr.feature.ar.anchor

import com.hereliesaz.graffitixr.feature.ar.anchor.StandaloneFingerprintBuilder.DetectedFeatures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [CloseUpFeatures]: which close-ups, and their features back in the photo's pixels. */
class CloseUpFeaturesTest {

    private val w = 1600
    private val h = 1200

    @Test
    fun `zooms 6, 3 and 2, each tiled by its aims, every crop inside the photo`() {
        val views = CloseUpFeatures.views(w, h, maxZoom = 6f)
        assertEquals(mapOf(6f to 36, 3f to 9, 2f to 4), views.groupingBy { it.zoom }.eachCount())
        for (v in views) {
            assertTrue(v.toString(), v.x >= 0 && v.y >= 0 && v.x + v.w <= w && v.y + v.h <= h)
            assertEquals(v.toString(), w / v.zoom, v.w.toFloat(), 1f)
            assertEquals(v.toString(), h / v.zoom, v.h.toFloat(), 1f)
        }
        // Each zoom's crops cover the whole photo.
        for (zoom in listOf(6f, 3f, 2f)) {
            val crops = views.filter { it.zoom == zoom }
            for (y in 0 until h step 50) for (x in 0 until w step 50) {
                assertTrue("zoom $zoom ($x,$y)", crops.any { x in it.x until it.x + it.w && y in it.y until it.y + it.h })
            }
        }
        assertTrue(CloseUpFeatures.views(0, h).isEmpty())
        // Two half-spheres: only zoom 2 (a 2 x 2 grid).
        assertEquals(4, CloseUpFeatures.views(w, h, distance = 2).size)
    }

    @Test
    fun `without a metric distance the zoom stops at 3, nearer than most lenses focus`() {
        // The zoom-6 grid (36 of 49 crops) is a sixth of the capture distance: blur on a phone.
        assertEquals(mapOf(3f to 9, 2f to 4), CloseUpFeatures.views(w, h).groupingBy { it.zoom }.eachCount())
        assertEquals(CloseUpFeatures.NON_METRIC_MAX_ZOOM, CloseUpFeatures.maxZoomFor(null))
        // Captured from 1.5 m with a lens focusing from 10 cm (+25 %): zoom 12 is still sharp, all three kept.
        assertEquals(12f, CloseUpFeatures.maxZoomFor(1.5, 0.10), 1e-4f)
        assertEquals(3, CloseUpFeatures.views(w, h, maxZoom = CloseUpFeatures.maxZoomFor(1.5, 0.10)).map { it.zoom }.distinct().size)
        // Captured from 30 cm with a 10 cm lens: only zoom 2 (2.4 max).
        assertEquals(setOf(2f), CloseUpFeatures.views(w, h, maxZoom = CloseUpFeatures.maxZoomFor(0.30, 0.10)).map { it.zoom }.toSet())
    }

    @Test
    fun `coarsest crops come first and the quick set is a leading prefix`() {
        val views = CloseUpFeatures.views(w, h)
        assertEquals(views.sortedBy { it.zoom }.map { it.zoom }, views.map { it.zoom })
        val quick = views.count { it.zoom <= CloseUpFeatures.QUICK_MAX_ZOOM }
        assertEquals(4, quick)
        assertTrue(quick < views.size)
        assertTrue(views.take(quick).all { it.zoom <= CloseUpFeatures.QUICK_MAX_ZOOM })
    }

    @Test
    fun `close-ups are about a live frame's size, and their features land back on the photo's pixels`() {
        val v = CloseUpFeatures.View(400, 300, 267, 200, 6f)
        val (outW, outH) = CloseUpFeatures.outputSize(v, w, h)
        assertEquals(CloseUpFeatures.MAX_VIEW_SIDE, outW)
        assertEquals(719, outH)
        // The close-up's corner pixels map to the crop's corner pixels.
        val d = DetectedFeatures(floatArrayOf(0f, 0f, outW - 1f, outH - 1f), ByteArray(64), 2, 32, 0)
        val back = CloseUpFeatures.mapBack(d, v, outW, outH).positions
        val sx = v.w.toFloat() / outW; val sy = v.h.toFloat() / outH
        assertArrayEquals(
            floatArrayOf(400 + 0.5f * sx - 0.5f, 300 + 0.5f * sy - 0.5f, 400 + 266.5f + 0.5f - 0.5f * sx - 0.5f, 300 + 199.5f + 0.5f - 0.5f * sy - 0.5f),
            back, 1e-3f,
        )
    }

    @Test
    fun `budgets, truncation and concatenation keep rows and descriptors together`() {
        assertEquals(30, CloseUpFeatures.perViewBudget(1500, 49))
        assertEquals(40, CloseUpFeatures.perViewBudget(5000, 49))
        assertEquals(0, CloseUpFeatures.perViewBudget(1500, 0))
        val a = DetectedFeatures(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), ByteArray(3 * 32) { (it / 32).toByte() }, 3, 32, 0)
        val t = CloseUpFeatures.truncated(a, 2)
        assertEquals(2, t.rows)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f), t.positions, 0f)
        assertEquals(64, t.descriptorsData.size)
        val b = DetectedFeatures(floatArrayOf(7f, 8f), ByteArray(32) { 9 }, 1, 32, 0)
        val c = CloseUpFeatures.concat(listOf(t, b))!!
        assertEquals(3, c.rows)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 7f, 8f), c.positions, 0f)
        assertEquals(9.toByte(), c.descriptorsData[2 * 32])
        // Another descriptor layout is left out, not mixed in.
        val sp = DetectedFeatures(floatArrayOf(0f, 0f), ByteArray(1024), 1, 256, 5)
        assertEquals(3, CloseUpFeatures.concat(listOf(c, sp))!!.rows)
        assertNull(CloseUpFeatures.concat(emptyList()))
    }

    @Test
    fun `a SuperPoint close-up is no bigger than its network input`() {
        val v = CloseUpFeatures.View(400, 300, 267, 200, 6f)
        val (outW, outH) = CloseUpFeatures.outputSize(v, w, h, CloseUpFeatures.SUPERPOINT_VIEW_SIDE)
        assertEquals(640, outW)
        assertEquals(479, outH)
        // The default is unchanged: ORB keeps the live-frame size.
        assertEquals(CloseUpFeatures.MAX_VIEW_SIDE, CloseUpFeatures.outputSize(v, w, h).first)
        // A photo smaller than the cap is never enlarged past its own long side.
        assertEquals(500, CloseUpFeatures.outputSize(CloseUpFeatures.View(0, 0, 100, 75, 2f), 500, 375, 640).first)
    }

    @Test
    fun `features inside a crop are counted by its half-open pixel rectangle`() {
        val v = CloseUpFeatures.View(100, 200, 50, 40, 6f)
        // Corner (100, 200) is in; (150, 200) and (100, 240) are on the far edges, out.
        val pos = floatArrayOf(100f, 200f, 149.9f, 239.9f, 150f, 200f, 100f, 240f, 10f, 10f)
        assertEquals(2, CloseUpFeatures.featuresIn(v, pos))
        assertEquals(0, CloseUpFeatures.featuresIn(v, FloatArray(0)))
    }

    @Test
    fun `finer crops over bare wall are dropped, the rest densest first, coarser crops untouched`() {
        val views = CloseUpFeatures.views(w, h, maxZoom = 6f)
        assertEquals(49, views.size)
        // No base features at all: only the crops every build has always made remain.
        val bare = CloseUpFeatures.prioritized(views, FloatArray(0))
        assertEquals(views.filter { it.zoom <= CloseUpFeatures.NON_METRIC_MAX_ZOOM }, bare)
        assertEquals(13, bare.size)
        // Null base positions: nothing is touched.
        assertEquals(views, CloseUpFeatures.prioritized(views, null))
        // Marks in two zoom-6 cells (one with 3 features, one with 1): both kept, denser first.
        val fine = views.filter { it.zoom == 6f }
        val a = fine[7]; val b = fine[20]
        val pos = floatArrayOf(
            b.x + 1f, b.y + 1f,
            a.x + 1f, a.y + 1f, a.x + 2f, a.y + 2f, a.x + 3f, a.y + 3f,
        )
        val out = CloseUpFeatures.prioritized(views, pos)
        assertEquals(13 + 2, out.size)
        assertEquals(listOf(a, b), out.filter { it.zoom == 6f })
        // Still coarsest first, so the quick set stays a leading prefix.
        assertEquals(out.sortedBy { it.zoom }.map { it.zoom }, out.map { it.zoom })
        assertTrue(out.take(4).all { it.zoom <= CloseUpFeatures.QUICK_MAX_ZOOM })
    }

    @Test
    fun `equally dense finer crops keep their grid order`() {
        val fine = CloseUpFeatures.views(w, h, maxZoom = 6f).filter { it.zoom == 6f }
        val pos = floatArrayOf(fine[30].x + 1f, fine[30].y + 1f, fine[3].x + 1f, fine[3].y + 1f)
        assertEquals(listOf(fine[3], fine[30]), CloseUpFeatures.prioritized(fine, pos))
    }

    @Test
    fun `the tail's clock starts with the quick set and only stops the crops past it`() {
        var t = 0L
        val budget = CloseUpFeatures.FineBudget(1_000L, { t })
        // Before start there is no deadline.
        t = 1_000_000L
        assertEquals(false, budget.exhausted(6f))
        t = 5_000L
        budget.start()
        t = 5_999L
        assertEquals(false, budget.exhausted(6f))
        t = 6_000L
        assertEquals(true, budget.exhausted(3f))
        assertEquals(true, budget.exhausted(6f))
        // The quick set is never cut, however late.
        assertEquals(false, budget.exhausted(CloseUpFeatures.QUICK_MAX_ZOOM))
        // A second start does not move the deadline.
        t = 7_000L
        budget.start()
        assertEquals(true, budget.exhausted(6f))
    }

    @Test
    fun `progress counts close-ups done of the total`() {
        // The bar is the current stage's progress, not the whole build's.
        assertEquals(0.5f, DoodleBuildProgress.Step(2, 3, 2, 4, 15, 50).fraction, 0f)
        assertEquals(0f, DoodleBuildProgress.Step(1, 3, 0, 0, 0, 0).fraction, 0f)
        assertNull(DoodleBuildProgress.state.value)
    }
}
