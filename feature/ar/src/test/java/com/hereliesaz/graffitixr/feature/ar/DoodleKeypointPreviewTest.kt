package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** [DoodleKeypointPreview]: the review's keypoints split by the marks mask, by the builders' own rule. */
class DoodleKeypointPreviewTest {

    @Test
    fun `features under the mask are kept, the rest dropped, in order`() {
        // A 100 x 100 photo, mask set only on its left half.
        val pos = floatArrayOf(10f, 10f, 90f, 10f, 49.9f, 50f, 50f, 50f)
        val s = DoodleKeypointPreview.split(pos, 100, 100, 100, 100) { x, _ -> x < 50 }
        assertArrayEquals(floatArrayOf(10f, 10f, 49.9f, 50f), s.kept, 0f)
        assertArrayEquals(floatArrayOf(90f, 10f, 50f, 50f), s.dropped, 0f)
        assertEquals(2, s.keptCount)
        assertEquals(2, s.droppedCount)
    }

    @Test
    fun `a mask of another size is sampled at the scaled, truncated pixel, as the builders do`() {
        // Photo 200 x 100, mask 100 x 50: photo (199.9, 99.9) is mask pixel (99, 49).
        val seen = ArrayList<Pair<Int, Int>>()
        DoodleKeypointPreview.split(floatArrayOf(199.9f, 99.9f, 3f, 5f), 200, 100, 100, 50) { x, y -> seen += x to y; true }
        assertEquals(listOf(99 to 49, 1 to 2), seen)
    }

    @Test
    fun `positions outside the photo are clamped into the mask, not dropped`() {
        val seen = ArrayList<Pair<Int, Int>>()
        DoodleKeypointPreview.split(floatArrayOf(-5f, 500f), 100, 100, 100, 100) { x, y -> seen += x to y; true }
        assertEquals(listOf(0 to 99), seen)
    }

    @Test
    fun `non-finite positions and empty input give nothing`() {
        val s = DoodleKeypointPreview.split(floatArrayOf(Float.NaN, 1f, 2f, Float.POSITIVE_INFINITY), 10, 10, 10, 10) { _, _ -> true }
        assertEquals(0, s.keptCount + s.droppedCount)
        assertEquals(0, DoodleKeypointPreview.split(FloatArray(0), 10, 10, 10, 10) { _, _ -> true }.keptCount)
        assertEquals(0, DoodleKeypointPreview.split(floatArrayOf(1f, 1f), 0, 10, 10, 10) { _, _ -> true }.keptCount)
        // An odd trailing coordinate is ignored.
        assertEquals(1, DoodleKeypointPreview.split(floatArrayOf(1f, 1f, 2f), 10, 10, 10, 10) { _, _ -> true }.keptCount)
    }
}
