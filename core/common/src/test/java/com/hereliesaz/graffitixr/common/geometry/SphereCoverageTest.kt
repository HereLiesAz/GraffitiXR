package com.hereliesaz.graffitixr.common.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 of the spherical-coverage map (docs/SPHERESLAM_SPHERE_MAP.md): the compass-anchored
 * angular-coverage math behind the guided-sweep hint. Deterministic, so it is fully unit-tested
 * here even though the sweep UX it feeds is device-only.
 */
class SphereCoverageTest {

    @Test
    fun `empty coverage is zero and unanchored`() {
        val c = SphereCoverage()
        assertEquals(0f, c.coverageFraction(), 0f)
        assertFalse(c.hasWallHeading())
        assertEquals(0, c.thinHeadingsDegrees().size)
    }

    @Test
    fun `first observation auto-anchors the wall heading`() {
        val c = SphereCoverage()
        assertTrue(c.observe(200f))
        assertTrue(c.hasWallHeading())
    }

    @Test
    fun `a fresh viewable sector grows coverage, a repeat does not`() {
        val c = SphereCoverage(sectorCount = 12, viewableHalfAngleDeg = 85f)
        c.setWallHeading(100f)
        assertTrue("first observation at the anchor grows coverage", c.observe(100f))
        assertFalse("same heading again does not grow coverage", c.observe(100f))
        assertEquals(1f / 12f, c.coverageFraction(), 1e-6f)
        assertEquals("both in-arc samples are counted", 2, c.observationCount)
        assertEquals(11, c.thinHeadingsDegrees().size)
    }

    @Test
    fun `headings behind the wall are outside the viewable arc and ignored`() {
        val c = SphereCoverage(viewableHalfAngleDeg = 85f)
        c.setWallHeading(0f)
        assertFalse("180 deg away is behind the wall", c.observe(180f))
        assertFalse("120 deg away is past the viewable edge", c.observe(120f))
        assertEquals(0, c.observationCount)
        assertEquals(0f, c.coverageFraction(), 0f)
    }

    @Test
    fun `sweeping the whole viewable arc reaches full coverage`() {
        val sectors = 12
        val half = 85f
        val c = SphereCoverage(sectorCount = sectors, viewableHalfAngleDeg = half)
        c.setWallHeading(90f)
        val step = (2f * half) / sectors
        // One heading at each sector center across the arc.
        for (s in 0 until sectors) {
            val delta = -half + (s + 0.5f) * step
            c.observe(SphereCoverage.norm360(90f + delta))
        }
        assertEquals(1f, c.coverageFraction(), 1e-6f)
        assertEquals(0, c.thinHeadingsDegrees().size)
    }

    @Test
    fun `viewable arc wraps across the 0-360 seam`() {
        val c = SphereCoverage(viewableHalfAngleDeg = 85f)
        c.setWallHeading(350f)
        assertTrue("10 deg is +20 from a 350 deg anchor, well inside the arc", c.observe(10f))
        assertTrue("330 deg is -20 from the anchor", c.observe(330f))
        assertTrue(c.coverageFraction() > 0f)
    }

    @Test
    fun `non-finite headings are ignored`() {
        val c = SphereCoverage()
        c.setWallHeading(0f)
        assertFalse(c.observe(Float.NaN))
        assertEquals(0, c.observationCount)
    }

    @Test
    fun `reset clears coverage and the anchor`() {
        val c = SphereCoverage()
        c.observe(45f)
        c.reset()
        assertEquals(0f, c.coverageFraction(), 0f)
        assertFalse(c.hasWallHeading())
    }

    @Test
    fun `fromHeadingLog folds a log against a given wall heading`() {
        val c = SphereCoverage.fromHeadingLog(
            headingsDeg = floatArrayOf(90f, 80f, 100f, 300f /* outside, ignored */),
            wallHeadingDeg = 90f,
            sectorCount = 12,
            viewableHalfAngleDeg = 85f,
        )
        assertEquals(3, c.observationCount)
        assertTrue(c.coverageFraction() > 0f)
    }
}
