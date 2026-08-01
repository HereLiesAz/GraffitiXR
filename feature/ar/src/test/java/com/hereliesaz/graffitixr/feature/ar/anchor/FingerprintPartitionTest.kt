package com.hereliesaz.graffitixr.feature.ar.anchor

import com.hereliesaz.graffitixr.common.model.Fingerprint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
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
        val IDENTITY_16 = listOf(
            1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f,
        )
    }

    /** Identity model matrix: design centred on the world origin, spanning ±HALF_W by ±HALF_H. */
    private fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f,
    )

    /** Uniform in-plane scale `s`, as the composed overlay transform carries it. */
    private fun scaled(s: Float) = floatArrayOf(
        s, 0f, 0f, 0f, 0f, s, 0f, 0f, 0f, 0f, s, 0f, 0f, 0f, 0f, 1f,
    )

    private fun fp(
        points: List<Float>,
        regions: ByteArray = ByteArray(0),
        hw: Float = -1f,
        hh: Float = -1f,
        anchorCam: List<Float> = IDENTITY_16,
    ): Fingerprint {
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
            captureAnchorCam = anchorCam,
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
    // partition + the legacy reading
    // -------------------------------------------------------------------------------------

    /**
     * A deliberately awkward rigid transform: 30° about Z composed with a translation, standing in
     * for `captureAnchorCam` (the anchor's pose in the capture camera frame).
     */
    private fun rigid30(): FloatArray {
        val c = kotlin.math.cos(Math.toRadians(30.0)).toFloat()
        val sn = kotlin.math.sin(Math.toRadians(30.0)).toFloat()
        // Written out rather than built with android.opengl.Matrix: that class is a STUB under a
        // plain JVM unit test — `setIdentityM` leaves the array all zeros and does not throw — so
        // building fixtures with it makes every point land at the origin and every assertion below
        // pass for the wrong reason. Verified by probe, not assumed.
        return floatArrayOf(
            c, sn, 0f, 0f,
            -sn, c, 0f, 0f,
            0f, 0f, 1f, 0f,
            0.3f, -1.7f, 4.0f, 1f,
        )
    }

    /**
     * A design pose that is NOT the identity and NOT a pure translation.
     *
     * This matters more than it looks. `partition` computes `captureAnchorCam · designAnchorLocal`,
     * and with an identity design the two operands commute — so the single most likely defect,
     * writing the multiply the other way round, is invisible. An earlier version of this file used
     * the identity here and passed with the operands swapped.
     */
    private fun designPose(): FloatArray {
        val c = kotlin.math.cos(Math.toRadians(50.0)).toFloat()
        val sn = kotlin.math.sin(Math.toRadians(50.0)).toFloat()
        return floatArrayOf(
            c, sn, 0f, 0f,
            -sn, c, 0f, 0f,
            0f, 0f, 1f, 0f,
            1f, 2f, -0.5f, 1f,
        )
    }

    private fun apply(m: FloatArray, x: Float, y: Float, z: Float) = floatArrayOf(
        m[0] * x + m[4] * y + m[8] * z + m[12],
        m[1] * x + m[5] * y + m[9] * z + m[13],
        m[2] * x + m[6] * y + m[10] * z + m[14],
    )

    /**
     * The composition `partition` rests on, checked against an independently-constructed
     * expectation rather than by re-deriving its own arithmetic.
     *
     * Points are placed at known positions in the DESIGN's own frame, pushed out to the capture
     * camera frame through the same chain the app builds them with, and the regions that come back
     * must be the ones the design-frame positions imply. A composition applied in the wrong order,
     * or with either factor inverted, moves the points somewhere else entirely.
     */
    @Test
    fun `partition composes the capture and design frames in the right order`() {
        val anchorCam = rigid30()
        val design = designPose()
        // Design-frame positions: centre, exactly on the +X edge, far outside, outside via +Y.
        val local = listOf(
            Triple(0f, 0f, 0f),
            Triple(HALF_W, 0f, 0f),
            Triple(5f, 0f, 0f),
            Triple(0.5f, 1.5f, 0f),
        )
        // design-local -> anchor-local -> capture camera frame.
        val pts = ArrayList<Float>()
        for ((x, y, z) in local) {
            val inAnchor = apply(design, x, y, z)
            val inCam = apply(anchorCam, inAnchor[0], inAnchor[1], inAnchor[2])
            pts.add(inCam[0]); pts.add(inCam[1]); pts.add(inCam[2])
        }

        val out = FingerprintPartition.partition(
            fp(pts, anchorCam = anchorCam.toList()),
            FingerprintPartition.DesignFootprint(design, HALF_W, HALF_H),
        )
        assertEquals(INSIDE, out.regions[0])
        assertEquals(BAND, out.regions[1])
        assertEquals(OUTSIDE, out.regions[2])
        assertEquals(OUTSIDE, out.regions[3])
        assertEquals(HALF_W, out.captureHalfW, 1e-6f)
        assertEquals(HALF_H, out.captureHalfH, 1e-6f)
    }

    /**
     * The negative half. Swapping the two factors — `design · anchorCam` instead of
     * `anchorCam · design` — must produce a different answer, or the order is untested and the test
     * above proves only that the code runs.
     */
    @Test
    fun `the two frame factors do not commute on this fixture`() {
        val anchorCam = rigid30()
        val design = designPose()
        val pts = floatArrayOf(0f, 0f, 0f, 1.5f, 0.2f, 0f, 3f, 0f, 0f)
        val right = FingerprintPartition.classify(
            pts, PoseMath.multiply(anchorCam, design), HALF_W, HALF_H,
        )
        val wrong = FingerprintPartition.classify(
            pts, PoseMath.multiply(design, anchorCam), HALF_W, HALF_H,
        )
        assertFalse(
            "the fixture must distinguish the operand order, or the test above is blind to it",
            right.contentEquals(wrong),
        )
    }

    @Test
    fun `partition records the design size it used, and a resize changes the answer`() {
        val anchorCam = rigid30()
        val design = designPose()
        // 1.5 along the design's own +X: outside a half-width of 1.0, inside a half-width of 2.0.
        val inAnchor = apply(design, 1.5f, 0f, 0f)
        val inCam = apply(anchorCam, inAnchor[0], inAnchor[1], inAnchor[2])
        val base = fp(inCam.toList(), anchorCam = anchorCam.toList())

        val small = FingerprintPartition.partition(
            base, FingerprintPartition.DesignFootprint(design, HALF_W, HALF_H),
        )
        assertEquals(OUTSIDE, small.regions[0])

        val big = FingerprintPartition.partition(
            small, FingerprintPartition.DesignFootprint(design, 2f, 1f),
        )
        assertEquals("a wider design must swallow the point", INSIDE, big.regions[0])
        assertEquals(2f, big.captureHalfW, 1e-6f)
        assertTrue("the old size must now read as stale", small.isPartitionStale(2f, 1f))
    }

    /**
     * Every refusal returns the RECEIVER, so a caller can skip a native re-push by identity. Each is
     * a case where partitioning would mean guessing a frame or a size, and a guessed partition is a
     * complete, plausible, wrong answer — whereas leaving it alone reproduces `main` exactly.
     */
    @Test
    fun `partition refuses rather than guesses`() {
        val design = FingerprintPartition.DesignFootprint(designPose(), HALF_W, HALF_H)

        val noAnchorCam = fp(listOf(0f, 0f, 0f), anchorCam = emptyList())
        assertSame("no captureAnchorCam: no frame to compose through",
            noAnchorCam, FingerprintPartition.partition(noAnchorCam, design))

        val noPoints = fp(emptyList())
        assertSame("no points: nothing to classify",
            noPoints, FingerprintPartition.partition(noPoints, design))

        val ok = fp(listOf(0f, 0f, 0f))
        assertSame("degenerate extents: Φ is undefined",
            ok, FingerprintPartition.partition(
                ok, FingerprintPartition.DesignFootprint(designPose(), 0f, 1f)))
    }

    /**
     * The case that must work and did not in the first cut of this phase: a fingerprint captured
     * before any artwork was placed. Target creation is what establishes the anchor the artwork sits
     * on, so EVERY first capture is unpartitioned — and if `partition` refused those, the feature
     * would be inert on the only target the artist actually paints against.
     */
    @Test
    fun `an unpartitioned fingerprint is exactly what partition is for`() {
        val anchorCam = rigid30()
        val design = designPose()
        val inAnchor = apply(design, 0f, 0f, 0f)
        val inCam = apply(anchorCam, inAnchor[0], inAnchor[1], inAnchor[2])
        val fresh = fp(inCam.toList(), anchorCam = anchorCam.toList())

        assertTrue("a fresh capture carries no partition", fresh.isUnpartitioned())
        assertFalse("...and is therefore never 'stale'", fresh.isPartitionStale(HALF_W, HALF_H))

        val out = FingerprintPartition.partition(
            fresh, FingerprintPartition.DesignFootprint(design, HALF_W, HALF_H),
        )
        assertFalse("partition must not gate on staleness", out.isUnpartitioned())
        assertEquals(INSIDE, out.regions[0])
    }

    @Test
    fun `staleness tracks the recorded extents`() {
        val f = FingerprintPartition.partition(
            fp(listOf(0f, 0f, 0f)),
            FingerprintPartition.DesignFootprint(designPose(), HALF_W, HALF_H),
        )
        assertFalse("same size is not stale", f.isPartitionStale(HALF_W, HALF_H))
        assertTrue("a resize is stale", f.isPartitionStale(HALF_W * 1.5f, HALF_H))
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
        val anchorCam = rigid30()
        val design = designPose()
        val pts = ArrayList<Float>()
        for ((x, y) in listOf(0f to 0f, 5f to 0f, HALF_W to 0f)) {
            val a = apply(design, x, y, 0f)
            val c = apply(anchorCam, a[0], a[1], a[2])
            pts.add(c[0]); pts.add(c[1]); pts.add(c[2])
        }
        val f = FingerprintPartition.partition(
            fp(pts, anchorCam = anchorCam.toList()),
            FingerprintPartition.DesignFootprint(design, HALF_W, HALF_H),
        )
        // inside, outside, band -> exactly one backbone point.
        assertEquals(1, FingerprintPartition.backboneCount(f))
    }

    /**
     * The capture-time `F_out` floor (2.8) counts through this overload, so the distinction it draws
     * has to be pinned: the BAND is **not** backbone. A "not enough bare wall" warning that counted
     * the band would stay silent on a design covering everything but its own border.
     */
    @Test
    fun `the raw backbone count excludes the band as well as the inside`() {
        assertEquals(1, FingerprintPartition.backboneCount(byteArrayOf(INSIDE, BAND, OUTSIDE)))
        assertEquals(0, FingerprintPartition.backboneCount(byteArrayOf(INSIDE, BAND, BAND)))
        assertEquals(3, FingerprintPartition.backboneCount(byteArrayOf(OUTSIDE, OUTSIDE, OUTSIDE)))
        assertEquals("no regions is no backbone at this level", 0, FingerprintPartition.backboneCount(ByteArray(0)))
    }

    @Test
    fun `a design with no extents is not usable`() {
        assertFalse(FingerprintPartition.DesignFootprint(identity(), 0f, 1f).isUsable)
        assertFalse(FingerprintPartition.DesignFootprint(identity(), 1f, -1f).isUsable)
        assertFalse(FingerprintPartition.DesignFootprint(FloatArray(4), 1f, 1f).isUsable)
        assertTrue(FingerprintPartition.DesignFootprint(identity(), 1f, 1f).isUsable)
    }

    /** The 1:1 invariant is enforced by the model, so a mismatched partition cannot be constructed. */
    @Test(expected = IllegalArgumentException::class)
    fun `a regions array that disagrees with the point count is rejected`() {
        fp(listOf(0f, 0f, 0f, 1f, 1f, 1f), regions = ByteArray(1))
    }
}
