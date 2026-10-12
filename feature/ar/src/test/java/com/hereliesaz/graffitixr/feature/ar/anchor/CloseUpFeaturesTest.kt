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
        val views = CloseUpFeatures.views(w, h)
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
    fun `progress counts close-ups done of the total`() {
        assertEquals(0.5f, FingerprintBuildProgress.Step(2, 4).fraction, 0f)
        assertEquals(0f, FingerprintBuildProgress.Step(0, 0).fraction, 0f)
        assertNull(FingerprintBuildProgress.state.value)
    }
}
