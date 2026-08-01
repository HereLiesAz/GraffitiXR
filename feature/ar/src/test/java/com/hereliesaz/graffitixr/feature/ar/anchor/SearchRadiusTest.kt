package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `IMPLEMENTATION.md` **4.2** — the search radius, asserted on **scaling relationships, not
 * absolutes**.
 *
 * That instruction is the point of the file. Every constant here is a pre-registered prior that E6,
 * E7 and E11 are expected to move, so a test pinning `r == 37.2f` would encode a guess as a
 * requirement and would have to be rewritten the moment the experiment it is waiting for reports.
 * What must not change under any sweep is the *shape*: twice the distance halves the radius, twice
 * the focal length doubles it, ρ enters linearly. Those are geometry, not tuning.
 *
 * The values are also what `MobileGS.cpp`'s transliteration has to reproduce, so a ratio that holds
 * here is a claim about both implementations.
 */
class SearchRadiusTest {

    private companion object {
        const val RHO = SearchRadius.RHO
        const val HALF_W = 0.8f
        const val HALF_H = 0.5f
        const val DIST_M = 2.0f
        const val FOCAL_PX = 1400f
        /** Not measured — DriftCostProbe's sentinel, so the drift term is out of the way. */
        const val NO_ERR = -1f
    }

    private fun r(
        rho: Float = RHO,
        halfW: Float = HALF_W,
        halfH: Float = HALF_H,
        dist: Float = DIST_M,
        focal: Float = FOCAL_PX,
        errMm: Float = NO_ERR,
        // Wide bounds so the clamp does not silently absorb the relationship under test. The clamp
        // has its own tests below.
        minPx: Float = 0f,
        maxPx: Float = 1e6f,
    ) = SearchRadius.pixels(rho, halfW, halfH, dist, focal, errMm, minPx = minPx, maxPx = maxPx)

    @Test
    fun `radius scales inversely with distance to the wall`() {
        val near = r(dist = 1.0f)
        val far = r(dist = 2.0f)
        assertEquals("doubling the distance must halve the radius", near / 2f, far, near * 1e-4f)
        assertTrue(far < near)
    }

    @Test
    fun `radius scales linearly with focal length`() {
        val short = r(focal = 700f)
        val long = r(focal = 1400f)
        assertEquals("doubling the focal length must double the radius", short * 2f, long, long * 1e-4f)
    }

    @Test
    fun `radius scales linearly with rho`() {
        val tight = r(rho = 0.05f)
        val loose = r(rho = 0.10f)
        assertEquals("doubling rho must double the radius", tight * 2f, loose, loose * 1e-4f)
    }

    /**
     * The design's size enters through the geometric mean, so a design scaled up by `k` on both axes
     * scales the radius by `k` — the property that makes ρ scale-free. A max- or min-based scalar
     * would also pass this; the anisotropy test below is what separates them.
     */
    @Test
    fun `radius scales linearly with a uniformly larger design`() {
        val small = r(halfW = 0.8f, halfH = 0.5f)
        val big = r(halfW = 1.6f, halfH = 1.0f)
        assertEquals(small * 2f, big, big * 1e-4f)
    }

    /**
     * Anisotropy is deliberately averaged, not maximised. The radius bounds how wrong a *pose*
     * prediction can be, and that error is isotropic in the image whatever the artwork's aspect
     * ratio — so a long thin design must not get a wider search than a square one of the same area.
     */
    @Test
    fun `a long thin design gets the same radius as a square one of equal area`() {
        val square = r(halfW = 1.0f, halfH = 1.0f)
        val thin = r(halfW = 4.0f, halfH = 0.25f)
        assertEquals("equal area must give equal radius", square, thin, square * 1e-4f)
        assertTrue("...and it must not be the max half-extent", thin < r(halfW = 4.0f, halfH = 4.0f))
    }

    // -------------------------------------------------------------------------------------
    // The drift term — 4.3
    // -------------------------------------------------------------------------------------

    /**
     * The phase's stated risk is a radius too tight after the pose has drifted, starving
     * corroboration exactly when it is needed. A measured error must therefore *widen* the search.
     */
    @Test
    fun `a measured pose error widens the radius`() {
        val steady = r(errMm = 0f)
        val drifting = r(errMm = 20f)
        assertTrue("20 mm of measured error must widen the search", drifting > steady)
    }

    /** With gain 1.0 the drift term is exactly the measured error in pixels, added on. */
    @Test
    fun `the drift term is the measured error projected into pixels`() {
        val pxPerMeter = FOCAL_PX / DIST_M
        val expectedDelta = 0.020f * pxPerMeter // 20 mm at gain 1.0
        assertEquals(expectedDelta, r(errMm = 20f) - r(errMm = 0f), expectedDelta * 1e-3f)
    }

    /**
     * `-1` is `DriftCostProbe`'s "not measured", and it is not zero. It must contribute **nothing**
     * rather than being read as a confident zero error — but it also must not invent a widening, so
     * unmeasured and zero happen to produce the same radius here. The distinction is asserted
     * against a *negative* value being treated as a magnitude, which would narrow or widen wrongly.
     */
    @Test
    fun `an unmeasured pose error contributes no drift term`() {
        assertEquals(r(errMm = 0f), r(errMm = -1f), 1e-4f)
        assertEquals("any negative sentinel behaves the same", r(errMm = 0f), r(errMm = -999f), 1e-4f)
    }

    // -------------------------------------------------------------------------------------
    // Clamping and degenerate inputs
    // -------------------------------------------------------------------------------------

    @Test
    fun `the radius is floored and ceiled`() {
        // Very far away and a tiny design: the scale term collapses toward zero.
        assertEquals(SearchRadius.MIN_PX, SearchRadius.pixels(RHO, 0.01f, 0.01f, 50f, 100f, NO_ERR), 0f)
        // Very close to a large design: the scale term explodes.
        assertEquals(SearchRadius.MAX_PX, SearchRadius.pixels(RHO, 5f, 5f, 0.2f, 3000f, NO_ERR), 0f)
    }

    /**
     * Unusable geometry yields the CEILING, not the floor, and the asymmetry is deliberate.
     *
     * A too-wide search costs precision, which the ratio test still filters. A too-tight one returns
     * no candidates at all and reads downstream as "the wall does not corroborate the design" — a
     * confident wrong answer rather than a degraded one, and indistinguishable from a genuinely
     * unpainted wall.
     */
    @Test
    fun `degenerate geometry fails wide, not narrow`() {
        val cases = mapOf(
            "zero distance" to SearchRadius.pixels(RHO, HALF_W, HALF_H, 0f, FOCAL_PX, NO_ERR),
            "negative distance" to SearchRadius.pixels(RHO, HALF_W, HALF_H, -1f, FOCAL_PX, NO_ERR),
            "zero focal length" to SearchRadius.pixels(RHO, HALF_W, HALF_H, DIST_M, 0f, NO_ERR),
            "no design extent" to SearchRadius.pixels(RHO, 0f, HALF_H, DIST_M, FOCAL_PX, NO_ERR),
            "zero rho" to SearchRadius.pixels(0f, HALF_W, HALF_H, DIST_M, FOCAL_PX, NO_ERR),
            "NaN distance" to SearchRadius.pixels(RHO, HALF_W, HALF_H, Float.NaN, FOCAL_PX, NO_ERR),
            "infinite focal" to
                SearchRadius.pixels(RHO, HALF_W, HALF_H, DIST_M, Float.POSITIVE_INFINITY, NO_ERR),
        )
        for ((name, value) in cases) {
            assertEquals("$name must fail wide", SearchRadius.MAX_PX, value, 0f)
        }
    }

    /** A NaN error must not poison the result into NaN — the clamp cannot rescue that. */
    @Test
    fun `a NaN pose error is ignored rather than propagated`() {
        val value = r(errMm = Float.NaN)
        assertTrue("got $value", value.isFinite())
        assertEquals(r(errMm = -1f), value, 1e-4f)
    }

    @Test
    fun `the pre-registered priors are the ones PARAMETERS documents`() {
        // Literal, so a silent edit to a value E6/E7/E11 are waiting on shows up as a test change
        // rather than as a number that quietly moved.
        assertEquals(0.05f, SearchRadius.RHO, 0f)
        assertEquals(4f, SearchRadius.MIN_PX, 0f)
        assertEquals(120f, SearchRadius.MAX_PX, 0f)
        assertEquals(1.0f, SearchRadius.ERR_GAIN, 0f)
    }
}
