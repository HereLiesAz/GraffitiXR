package com.hereliesaz.graffitixr.feature.ar.anchor

import com.hereliesaz.graffitixr.common.model.Fingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.KeyPoint

/**
 * `IMPLEMENTATION.md` **2.5** — the partition, on a synthetic straddling point set.
 *
 * The design sits in the XY plane at the world origin, so the expected region of each test point is
 * obvious by inspection and the assertions are not re-derivations of `Footprint`'s own arithmetic.
 * That matters here more than usual: the whole value of this file is catching a Φ applied the wrong
 * way round, and a test that computed its expectation with the same inverse would pass under
 * exactly that bug.
 */
class FingerprintPartitionTest {

    private companion object {
        const val HALF_W = 1.0f
        const val HALF_H = 0.5f
        val INSIDE = Footprint.Region.INSIDE.ordinal.toByte()
        val BAND = Footprint.Region.BAND.ordinal.toByte()
        val OUTSIDE = Footprint.Region.OUTSIDE.ordinal.toByte()
    }

    /** Identity model matrix: design centred on the world origin, spanning ±HALF_W by ±HALF_H. */
    private fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f,
    )

    /** Uniform in-plane scale `s`, as the composed overlay transform carries it. */
    private fun scaled(s: Float) = floatArrayOf(
        s, 0f, 0f, 0f, 0f, s, 0f, 0f, 0f, 0f, s, 0f, 0f, 0f, 0f, 1f,
    )

    private fun fp(points: List<Float>, regions: ByteArray = ByteArray(0), hw: Float = -1f, hh: Float = -1f): Fingerprint {
        val rows = points.size / 3
        return Fingerprint(
            keypoints = List(rows) { KeyPoint(1f, 1f, 7f) },
            points3d = points,
            descriptorsData = ByteArray(rows * 32),
            descriptorsRows = rows,
            descriptorsCols = 32,
            descriptorsType = 0,
            regions = regions,
            captureHalfW = hw,
            captureHalfH = hh,
        )
    }

    @Test
    fun `a point at the design centre is inside`() {
        val r = FingerprintPartition.classify(floatArrayOf(0f, 0f, 0f), identity(), HALF_W, HALF_H)
        assertEquals(INSIDE, r[0])
    }

    @Test
    fun `a point far beyond the design is outside`() {
        val r = FingerprintPartition.classify(floatArrayOf(5f, 0f, 0f), identity(), HALF_W, HALF_H)
        assertEquals(OUTSIDE, r[0])
    }

    /**
     * Exactly on the edge. This is the point the BAND exists for — its classification is the one
     * that flips as the artist paints out to the boundary, and admitting it to the backbone would
     * corrupt the set that is supposed to survive the whole job.
     */
    @Test
    fun `a point on the design edge lands in the band, not the backbone`() {
        val r = FingerprintPartition.classify(floatArrayOf(HALF_W, 0f, 0f), identity(), HALF_W, HALF_H)
        assertEquals("edge point must not be backbone", BAND, r[0])
        assertNotEquals(OUTSIDE, r[0])
    }

    /**
     * The non-square case. `HALF_W != HALF_H`, so a point that is inside along X and outside along Y
     * catches a Φ that normalized by the wrong axis — which a square design would hide completely.
     */
    @Test
    fun `non-square extents are normalized per axis`() {
        // x = 0.5 is well inside (half-width 1.0); y = 1.5 is well outside (half-height 0.5).
        val r = FingerprintPartition.classify(floatArrayOf(0.5f, 1.5f, 0f), identity(), HALF_W, HALF_H)
        assertEquals(OUTSIDE, r[0])
        // ...and the transpose is inside, which it would not be if the axes were swapped.
        val r2 = FingerprintPartition.classify(floatArrayOf(0.5f, 0.1f, 0f), identity(), HALF_W, HALF_H)
        assertEquals(INSIDE, r2[0])
    }

    /**
     * The highest-probability bug in the phase, per `IMPLEMENTATION.md`: a scaled anchor with the
     * wrong extents convention. Both combinations compile and both produce plausible numbers.
     *
     * With a 2× composed scale and UNSCALED extents, the design covers ±2.0 in world X. A point at
     * x = 1.5 is therefore INSIDE — whereas treating the same matrix as rigid would call it OUTSIDE.
     */
    @Test
    fun `a composed scale widens the footprint`() {
        val p = floatArrayOf(1.5f, 0f, 0f)
        val composed = FingerprintPartition.classify(p, scaled(2f), HALF_W, HALF_H, composed = true)
        assertEquals("2x scale must swallow x=1.5", INSIDE, composed[0])

        val asRigid = FingerprintPartition.classify(p, identity(), HALF_W, HALF_H, composed = false)
        assertEquals("unscaled design must not reach x=1.5", OUTSIDE, asRigid[0])
        assertNotEquals(
            "the two conventions must be distinguishable, or the flag is decorative",
            composed[0], asRigid[0],
        )
    }

    @Test
    fun `classification is one byte per point, in order`() {
        val pts = floatArrayOf(
            0f, 0f, 0f,     // inside
            5f, 0f, 0f,     // outside
            0.1f, 0f, 0f,   // inside
        )
        val r = FingerprintPartition.classify(pts, identity(), HALF_W, HALF_H)
        assertEquals(3, r.size)
        assertEquals(INSIDE, r[0]); assertEquals(OUTSIDE, r[1]); assertEquals(INSIDE, r[2])
    }

    @Test
    fun `an empty point set yields an empty partition`() {
        assertEquals(0, FingerprintPartition.classify(FloatArray(0), identity(), HALF_W, HALF_H).size)
    }

    // -------------------------------------------------------------------------------------
    // reclassify + the legacy reading
    // -------------------------------------------------------------------------------------

    @Test
    fun `reclassify tags a previously unpartitioned fingerprint and records the extents`() {
        val before = fp(listOf(0f, 0f, 0f, 5f, 0f, 0f))
        assertTrue(before.isUnpartitioned())

        val after = FingerprintPartition.reclassify(before, identity(), HALF_W, HALF_H)
        assertFalse(after.isUnpartitioned())
        assertEquals(2, after.regions.size)
        assertEquals(INSIDE, after.regions[0])
        assertEquals(OUTSIDE, after.regions[1])
        assertEquals(HALF_W, after.captureHalfW, 1e-6f)
        assertEquals(HALF_H, after.captureHalfH, 1e-6f)
    }

    /**
     * Growing the design must move points from backbone into the footprint. If `reclassify` silently
     * kept the old bytes, this would pass only by accident of the fixture — so the point chosen is
     * one whose region genuinely changes.
     */
    @Test
    fun `growing the design converts backbone into corroboration`() {
        val pts = listOf(1.5f, 0f, 0f)
        val small = FingerprintPartition.reclassify(fp(pts), identity(), HALF_W, HALF_H)
        assertEquals(OUTSIDE, small.regions[0])

        val big = FingerprintPartition.reclassify(small, identity(), 2f, 1f)
        assertEquals("a wider design must swallow the point", INSIDE, big.regions[0])
        assertEquals(2f, big.captureHalfW, 1e-6f)
    }

    @Test
    fun `staleness tracks the recorded extents`() {
        val f = FingerprintPartition.reclassify(fp(listOf(0f, 0f, 0f)), identity(), HALF_W, HALF_H)
        assertFalse("same size is not stale", f.isPartitionStale(HALF_W, HALF_H))
        assertTrue("a resize is stale", f.isPartitionStale(HALF_W * 1.5f, HALF_H))
    }

    /**
     * An unpartitioned fingerprint is never "stale" — there is nothing to recompute against, and
     * reporting it as stale would send every legacy project through a reclassify with extents it
     * was never built for.
     */
    @Test
    fun `an unpartitioned fingerprint is never stale`() {
        assertFalse(fp(listOf(0f, 0f, 0f)).isPartitionStale(99f, 99f))
    }

    /**
     * The legacy reading, and the one that must not be inverted: **empty regions mean all-backbone**.
     * Reading it as all-inside would exclude a pre-Phase-2 map from the reloc PnP entirely and the
     * target would simply never lock — a total failure that looks like bad tracking.
     */
    @Test
    fun `an unpartitioned fingerprint counts as all backbone`() {
        val legacy = fp(listOf(0f, 0f, 0f, 5f, 0f, 0f, 9f, 9f, 0f))
        assertEquals(3, FingerprintPartition.backboneCount(legacy))
    }

    @Test
    fun `backboneCount counts only OUTSIDE once partitioned`() {
        val f = FingerprintPartition.reclassify(
            fp(listOf(0f, 0f, 0f, 5f, 0f, 0f, HALF_W, 0f, 0f)), identity(), HALF_W, HALF_H,
        )
        // inside, outside, band -> exactly one backbone point.
        assertEquals(1, FingerprintPartition.backboneCount(f))
    }

    /** The 1:1 invariant is enforced by the model, so a mismatched partition cannot be constructed. */
    @Test(expected = IllegalArgumentException::class)
    fun `a regions array that disagrees with the point count is rejected`() {
        fp(listOf(0f, 0f, 0f, 1f, 1f, 1f), regions = ByteArray(1))
    }
}
