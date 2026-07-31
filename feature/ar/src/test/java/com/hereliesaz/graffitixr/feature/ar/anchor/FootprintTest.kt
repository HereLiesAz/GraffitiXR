package com.hereliesaz.graffitixr.feature.ar.anchor

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EVALUATION.md **E1** — correctness of the footprint operator Φ.
 *
 * Φ is consumed by every later phase of the hybrid relocalizer, so an error here is
 * indistinguishable downstream from a badly-chosen threshold: the partition would simply be wrong,
 * quietly, and every measurement taken on top of it would be uninterpretable. These cases are cheap
 * and exact, and they exist so that never happens.
 *
 * Three of them catch real bugs rather than restating the implementation:
 *  - [rotated anchor maps the design's own axes, not the world's] catches using the forward
 *    transform where the inverse was needed — the highest-probability mistake here, because both
 *    compile and both produce plausible-looking numbers.
 *  - [non-square extents saturate at each axis's own edge] catches dividing both axes by the same
 *    half-extent.
 *  - [scaled anchor still puts the design edge at one] catches the `rigidInverse` trap that the
 *    first draft of the implementation plan actively recommended.
 */
class FootprintTest {

    private companion object {
        const val EPS = 1e-4f
        const val HALF_W = 1.5f
        const val HALF_H = 0.75f
    }

    private fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    /** Column-major rigid transform: rotation [deg] about Z, then translation. */
    private fun anchor(deg: Float = 0f, tx: Float = 0f, ty: Float = 0f, tz: Float = 0f): FloatArray {
        val c = cos(Math.toRadians(deg.toDouble())).toFloat()
        val s = sin(Math.toRadians(deg.toDouble())).toFloat()
        return floatArrayOf(
            c, s, 0f, 0f,
            -s, c, 0f, 0f,
            0f, 0f, 1f, 0f,
            tx, ty, tz, 1f,
        )
    }

    /** As [anchor], but with the overlay's in-plane pinch folded in — `scaleM(.., s, s, 1f)`. */
    private fun composedAnchor(scale: Float, deg: Float = 0f, tx: Float = 0f, ty: Float = 0f): FloatArray {
        val m = anchor(deg, tx, ty)
        for (i in 0 until 3) {           // scale columns 0 and 1 (local X and Y), leave Z alone
            m[i] *= scale
            m[4 + i] *= scale
        }
        return m
    }

    @Test
    fun `identity anchor puts the centre at origin and the corner at one`() {
        val c = Footprint.of(identity(), HALF_W, HALF_H, 0f, 0f, 0f)
        assertEquals(0f, c[0], EPS); assertEquals(0f, c[1], EPS)

        val corner = Footprint.of(identity(), HALF_W, HALF_H, HALF_W, HALF_H, 0f)
        assertEquals(1f, corner[0], EPS); assertEquals(1f, corner[1], EPS)
    }

    @Test
    fun `translated anchor leaves the design's own coordinates unchanged`() {
        val a = anchor(tx = 7f, ty = -3f, tz = 12f)
        val centre = Footprint.of(a, HALF_W, HALF_H, 7f, -3f, 12f)
        assertEquals(0f, centre[0], EPS); assertEquals(0f, centre[1], EPS)

        val edge = Footprint.of(a, HALF_W, HALF_H, 7f + HALF_W, -3f, 12f)
        assertEquals(1f, edge[0], EPS); assertEquals(0f, edge[1], EPS)
    }

    /**
     * With the design rotated 45 degrees in its own plane, a point along the design's LOCAL +X axis
     * must map to (1, 0). Using the forward transform instead of the inverse rotates the answer
     * instead of un-rotating it, and lands somewhere near (0.71, 0.71) — plausible enough to pass a
     * sloppier assertion.
     */
    @Test
    fun `rotated anchor maps the design's own axes, not the world's`() {
        val deg = 45f
        val a = anchor(deg)
        val c = cos(Math.toRadians(deg.toDouble())).toFloat()
        val s = sin(Math.toRadians(deg.toDouble())).toFloat()
        // World position of the point sitting at local (HALF_W, 0, 0).
        val world = floatArrayOf(c * HALF_W, s * HALF_W, 0f)

        val uv = Footprint.of(a, HALF_W, HALF_H, world[0], world[1], world[2])
        assertEquals("local +X edge must map to u=1", 1f, uv[0], EPS)
        assertEquals("local +X edge must map to v=0", 0f, uv[1], EPS)
    }

    /** Each axis normalizes by its OWN half-extent; a shared divisor breaks the narrow one. */
    @Test
    fun `non-square extents saturate at each axis's own edge`() {
        val u = Footprint.of(identity(), HALF_W, HALF_H, HALF_W, 0f, 0f)
        assertEquals(1f, u[0], EPS); assertEquals(0f, u[1], EPS)

        val v = Footprint.of(identity(), HALF_W, HALF_H, 0f, HALF_H, 0f)
        assertEquals(0f, v[0], EPS); assertEquals(1f, v[1], EPS)
    }

    /**
     * THE trap. The composed overlay matrix carries `overlayScale`, and inverting it by transposing
     * (`rigidInverse`) is wrong by `s²`: at scale 2 the design edge reports 4 instead of 1, is
     * classified OUTSIDE, and every feature under the artwork is promoted into the backbone — the
     * set defined as wall that never gets painted.
     */
    @Test
    fun `scaled anchor still puts the design edge at one`() {
        for (scale in floatArrayOf(0.5f, 1f, 2f, 3.5f)) {
            val m = composedAnchor(scale)
            // At scale s the design's physical edge sits at s * HALF_W in world units.
            val edge = Footprint.ofComposed(m, HALF_W, HALF_H, scale * HALF_W, 0f, 0f)
            assertEquals("scale=$scale u", 1f, edge[0], EPS)
            assertEquals("scale=$scale v", 0f, edge[1], EPS)

            val centre = Footprint.ofComposed(m, HALF_W, HALF_H, 0f, 0f, 0f)
            assertEquals("scale=$scale centre u", 0f, centre[0], EPS)
        }
    }

    /** The same trap with rotation and translation present, so the scale fix cannot be a coincidence. */
    @Test
    fun `scaled, rotated and translated anchor still puts the edge at one`() {
        val scale = 2f
        val deg = 30f
        val m = composedAnchor(scale, deg, tx = -4f, ty = 9f)
        val c = cos(Math.toRadians(deg.toDouble())).toFloat()
        val s = sin(Math.toRadians(deg.toDouble())).toFloat()
        // Local (HALF_W, 0, 0) -> scaled by `scale`, rotated by `deg`, then translated.
        val world = floatArrayOf(-4f + c * scale * HALF_W, 9f + s * scale * HALF_W, 0f)

        val uv = Footprint.ofComposed(m, HALF_W, HALF_H, world[0], world[1], world[2])
        assertEquals(1f, uv[0], EPS)
        assertEquals(0f, uv[1], EPS)
    }

    /**
     * Using the preferred call shape — rigid anchor with the scale folded into the extents — must
     * agree exactly with [Footprint.ofComposed]. The plan offers both; if they disagree, one of the
     * two call sites in the codebase would be silently wrong.
     */
    @Test
    fun `folding scale into the extents matches dividing it out of the inverse`() {
        val scale = 2f
        val deg = 30f
        val rigid = anchor(deg, tx = -4f, ty = 9f)
        val composed = composedAnchor(scale, deg, tx = -4f, ty = 9f)
        val world = floatArrayOf(-1.2f, 10.4f, 0.3f)

        val viaExtents = Footprint.of(rigid, HALF_W * scale, HALF_H * scale, world[0], world[1], world[2])
        val viaInverse = Footprint.ofComposed(composed, HALF_W, HALF_H, world[0], world[1], world[2])
        assertEquals(viaExtents[0], viaInverse[0], EPS)
        assertEquals(viaExtents[1], viaInverse[1], EPS)
    }

    /**
     * Φ deliberately discards the plane-normal component: a point half a metre off the wall maps to
     * the same (u,v) as its foot. Asserted rather than merely documented, because a reader will
     * wonder whether it was intentional.
     */
    @Test
    fun `off-plane points project to the same footprint as their foot`() {
        val a = anchor(20f, tx = 1f, ty = 2f)
        val onWall = Footprint.of(a, HALF_W, HALF_H, 1.4f, 2.3f, 0f).copyOf()
        val offWall = Footprint.of(a, HALF_W, HALF_H, 1.4f, 2.3f, 0.5f)
        assertEquals(onWall[0], offWall[0], EPS)
        assertEquals(onWall[1], offWall[1], EPS)
    }

    @Test
    fun `isInside honours its margin`() {
        assertTrue(Footprint.isInside(floatArrayOf(0.99f, 0f)))
        assertTrue(!Footprint.isInside(floatArrayOf(1.01f, 0f)))
        assertTrue(Footprint.isInside(floatArrayOf(1.05f, 0f), margin = 0.1f))
    }

    /** Chebyshev, not Euclidean: a corner is not "further out" than a side at the same overshoot. */
    @Test
    fun `outsideDistance is zero inside and Chebyshev outside`() {
        assertEquals(0f, Footprint.outsideDistance(floatArrayOf(0.5f, -0.9f)), EPS)
        assertEquals(0.25f, Footprint.outsideDistance(floatArrayOf(1.25f, 0f)), EPS)
        assertEquals(0.25f, Footprint.outsideDistance(floatArrayOf(0f, -1.25f)), EPS)
        assertEquals(0.25f, Footprint.outsideDistance(floatArrayOf(1.25f, 1.25f)), EPS)
    }

    @Test
    fun `classify partitions into inside, band and outside`() {
        fun c(r: Float) = Footprint.classify(floatArrayOf(r, 0f), innerMargin = 0.05f, outerMargin = 0.15f)
        assertEquals(Footprint.Region.INSIDE, c(0.90f))
        assertEquals(Footprint.Region.INSIDE, c(0.95f))   // exactly on the inner boundary
        assertEquals(Footprint.Region.BAND, c(0.98f))
        assertEquals(Footprint.Region.BAND, c(1.10f))
        assertEquals(Footprint.Region.OUTSIDE, c(1.15f))  // exactly on the outer boundary
        assertEquals(Footprint.Region.OUTSIDE, c(1.30f))
    }

    /** The band uses the larger of the two axes, so a point outside in EITHER axis is not INSIDE. */
    @Test
    fun `classify uses the worse axis`() {
        val uv = floatArrayOf(0.1f, 1.3f)
        assertEquals(Footprint.Region.OUTSIDE, Footprint.classify(uv, 0.05f, 0.15f))
    }

    /**
     * Inverted margins would collapse the band to nothing and hand boundary features straight to the
     * backbone — the exact failure the band exists to prevent, and silent if it were allowed.
     */
    @Test
    fun `classify rejects inverted margins rather than producing an empty band`() {
        assertThrows(IllegalArgumentException::class.java) {
            Footprint.classify(floatArrayOf(1f, 0f), innerMargin = 0.2f, outerMargin = 0.1f)
        }
    }

    @Test
    fun `degenerate extents are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Footprint.of(identity(), 0f, HALF_H, 1f, 1f, 0f)
        }
    }

    /** The buffer overload exists to keep Φ out of the allocator; it must write in place. */
    @Test
    fun `caller-supplied buffer is filled and returned`() {
        val buf = FloatArray(2)
        val r = Footprint.of(identity(), HALF_W, HALF_H, HALF_W, HALF_H, 0f, buf)
        assertTrue("must return the caller's buffer, not a copy", r === buf)
        assertEquals(1f, buf[0], EPS)
        assertEquals(1f, buf[1], EPS)
    }
}
