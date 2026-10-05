package com.hereliesaz.graffitixr.common.geometry

import kotlin.math.floor

/**
 * Angular-coverage accumulator for the standalone guided sweep — Phase 3 of the spherical-coverage
 * map (`docs/SPHERESLAM_SPHERE_MAP.md`), compass-anchored.
 *
 * A wall can only be seen from in front of it: standing behind it, or edge-on, the camera sees
 * nothing of the surface. So coverage is tracked not over the full circle but over the **viewable
 * front arc** — `±`[viewableHalfAngleDeg] around the heading the artist faces when the wall is
 * captured head-on ([wallHeadingDeg], the wall normal's compass bearing). Headings outside that arc
 * are physically useless and ignored, so "100%" means the reachable arc is mapped, not an impossible
 * full 360°.
 *
 * Bearings are **absolute compass headings in degrees** (0 = magnetic north, clockwise). Absolute,
 * magnetometer-anchored heading is drift-free over a long pivot where the bare gyro would wander
 * (design §9.2), and gives a fixed frame the viewable arc can be defined in.
 *
 * Pure, frame-free math: it feeds the "which directions are still thin" hint and gates nothing
 * (§9.1). Thread-safe via the view model that owns it.
 */
class SphereCoverage(
    private val sectorCount: Int = DEFAULT_SECTORS,
    private val viewableHalfAngleDeg: Float = DEFAULT_VIEWABLE_HALF_ANGLE_DEG,
) {
    init {
        require(sectorCount >= 2) { "sectorCount must be >= 2, was $sectorCount" }
        require(viewableHalfAngleDeg in 1f..180f) {
            "viewableHalfAngleDeg must be in (0, 180], was $viewableHalfAngleDeg"
        }
    }

    private val hits = IntArray(sectorCount)
    private var total = 0

    /**
     * Compass heading (degrees) the camera faces when the wall is captured head-on — the center of
     * the viewable arc. Null until anchored; the first observation auto-anchors to its own heading so
     * a sweep still works when the reference heading was never recorded.
     */
    private var wallHeadingDeg: Float? = null

    val observationCount: Int get() = total

    /** Set (or re-set) the wall-facing heading. Does not clear accumulated sectors. */
    fun setWallHeading(headingDeg: Float) {
        if (headingDeg.isFinite()) wallHeadingDeg = norm360(headingDeg)
    }

    /** Whether a wall-facing heading has been anchored (explicitly or by the first observation). */
    fun hasWallHeading(): Boolean = wallHeadingDeg != null

    /**
     * Fold one absolute compass [headingDeg] into the coverage. Auto-anchors the wall heading on the
     * first call if unset. Returns true when it landed in a viewable sector not previously covered
     * (coverage grew); false when non-finite or outside the viewable front arc.
     */
    fun observe(headingDeg: Float): Boolean {
        if (!headingDeg.isFinite()) return false
        val anchor = wallHeadingDeg ?: norm360(headingDeg).also { wallHeadingDeg = it }
        val sector = sectorOf(headingDeg, anchor) ?: return false
        total++
        val wasEmpty = hits[sector] == 0
        hits[sector]++
        return wasEmpty
    }

    /** Fraction of the viewable arc's sectors with at least one observation, in `[0, 1]`. */
    fun coverageFraction(): Float {
        var covered = 0
        for (h in hits) if (h > 0) covered++
        return covered.toFloat() / sectorCount
    }

    /**
     * Absolute compass headings (degrees) of the still-unobserved viewable sectors — where the sweep
     * hint should send the artist. Empty before anchoring and once the arc is covered.
     */
    fun thinHeadingsDegrees(): FloatArray {
        val anchor = wallHeadingDeg ?: return FloatArray(0)
        val span = 2f * viewableHalfAngleDeg
        val step = span / sectorCount
        val out = ArrayList<Float>()
        for (s in 0 until sectorCount) {
            if (hits[s] == 0) {
                val delta = -viewableHalfAngleDeg + (s + 0.5f) * step
                out.add(norm360(anchor + delta))
            }
        }
        return out.toFloatArray()
    }

    /** Drop all accumulated coverage and the anchor (new canonical frame / reference reset). */
    fun reset() {
        hits.fill(0)
        total = 0
        wallHeadingDeg = null
    }

    /** Sector index for [headingDeg] within the viewable arc around [anchor], or null if outside it. */
    private fun sectorOf(headingDeg: Float, anchor: Float): Int? {
        val delta = signedDelta(norm360(headingDeg), anchor) // [-180, 180]
        if (delta < -viewableHalfAngleDeg || delta > viewableHalfAngleDeg) return null
        val span = 2f * viewableHalfAngleDeg
        val step = span / sectorCount
        val s = floor((delta + viewableHalfAngleDeg) / step).toInt()
        return s.coerceIn(0, sectorCount - 1)
    }

    companion object {
        /** Sectors across the viewable arc — ~15° each at the default half-angle. */
        const val DEFAULT_SECTORS = 12

        /**
         * Half-width of the viewable front arc. 85° (≈170° total) keeps the near-grazing edges, where
         * the wall is barely resolvable, out of the coverage target. Tunable on device (Phase 4).
         */
        const val DEFAULT_VIEWABLE_HALF_ANGLE_DEG = 85f

        /** Normalize a heading to [0, 360). */
        fun norm360(deg: Float): Float = ((deg % 360f) + 360f) % 360f

        /** Shortest signed angular difference a−b in degrees, in [-180, 180]. */
        private fun signedDelta(a: Float, b: Float): Float {
            var d = (a - b + 180f) % 360f
            if (d < 0f) d += 360f
            return d - 180f
        }

        /**
         * Build coverage from a parallel heading log and a wall-facing heading. Headings outside the
         * viewable arc are ignored by [observe].
         */
        fun fromHeadingLog(
            headingsDeg: FloatArray,
            wallHeadingDeg: Float? = null,
            sectorCount: Int = DEFAULT_SECTORS,
            viewableHalfAngleDeg: Float = DEFAULT_VIEWABLE_HALF_ANGLE_DEG,
        ): SphereCoverage {
            val c = SphereCoverage(sectorCount, viewableHalfAngleDeg)
            if (wallHeadingDeg != null) c.setWallHeading(wallHeadingDeg)
            for (h in headingsDeg) c.observe(h)
            return c
        }
    }
}
