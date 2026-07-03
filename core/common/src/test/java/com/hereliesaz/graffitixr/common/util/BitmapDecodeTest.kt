package com.hereliesaz.graffitixr.common.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `BitmapFactory.decodeByteArray` is Android-framework-only, so the two-pass wrapper itself is
 * exercised by device tests. Here we lock the pure-Kotlin helper that determines the
 * `inSampleSize` used by the second pass — this is the piece that gates the OOM risk from the
 * co-op `LayerBitmapReplace` decode path.
 */
class BitmapDecodeTest {

    @Test
    fun `small image returns 1 - no over-sampling`() {
        assertEquals(1, computeSampleSize(1000, 1000, 2000))
        assertEquals(1, computeSampleSize(2000, 2000, 2000))
    }

    @Test
    fun `4x oversized image samples down to 4`() {
        // 4096 / 4 = 1024, so sample = 4 keeps the longest edge under 2000.
        assertEquals(4, computeSampleSize(4096, 4096, 2000))
    }

    @Test
    fun `2x oversized image samples down to 2`() {
        // 3000 / 2 = 1500, so sample = 2 keeps the longest edge under 2000.
        assertEquals(2, computeSampleSize(3000, 3000, 2000))
    }

    @Test
    fun `sample size is powers of two only`() {
        // 5000 / 4 = 1250 < 2000, so sample = 4 (not 3).
        assertEquals(4, computeSampleSize(5000, 5000, 2000))
        // 9000 / 8 = 1125 < 2000, so sample = 8 (not 5).
        assertEquals(8, computeSampleSize(9000, 9000, 2000))
    }

    @Test
    fun `non-square uses the longest edge as the constraint`() {
        // Wide: 8000 x 400. 8000/4 = 2000, condition is strict > so sample=4 exits. Sample = 4.
        assertEquals(4, computeSampleSize(8000, 400, 2000))
        // Tall: 400 x 8000 — same result.
        assertEquals(4, computeSampleSize(400, 8000, 2000))
    }
}
