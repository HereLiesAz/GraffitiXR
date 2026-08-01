package com.hereliesaz.graffitixr.feature.ar.anchor

import kotlin.math.sqrt

/**
 * `IMPLEMENTATION.md` **Phase 4** — how far from a predicted pixel the corroboration match is
 * allowed to look.
 *
 * Once `F_out` has produced a pose, every `F_in` point has a *predicted* image location, so matching
 * stops being a global search and becomes a local one. `PAPER.md` §5.5's worked example puts the
 * candidate set at roughly 0.2% of the design's features at `ρ = 0.05` — and that collapse in
 * false positives is what lets the Lowe ratio be *loosened* (`CORROB_LOWE_RATIO`, 0.85 against the
 * reloc path's 0.75) instead of tightened. A constraint buying back recall is the phase's entire
 * justification, which is why E6/E7 measure both sides of it.
 *
 * This is the pure-Kotlin reference. `MobileGS.cpp` transliterates it, and
 * `SearchRadiusTest` pins the scaling relationships the transliteration has to preserve.
 *
 * ## Why the radius is not a constant
 *
 * The stated risk for this phase is a radius that is too tight *after the pose has drifted* —
 * starving corroboration exactly when it is most needed, and doing it silently. So `r` has two
 * terms:
 *
 *  - a **scale** term, `ρ` × the design's on-screen size, which keeps the search scale-free: the
 *    same fraction of the artwork whether the artist is a metre away or five;
 *  - a **drift** term, the measured recent pose error projected into pixels, which widens the search
 *    precisely when the prediction is known to be unreliable.
 *
 * The drift term reads `errMm` from `DriftCostProbe` rather than a fixed allowance, because a fixed
 * allowance is either too tight when drifting or too loose when not, and there is no value that is
 * both.
 */
object SearchRadius {

    /**
     * Search radius in pixels for a corroboration match.
     *
     * @param rho normalized search radius, as a fraction of the design's half-extent.
     * @param designHalfWMeters the design's **effective** (scale-included) half-width in metres.
     * @param designHalfHMeters likewise the half-height.
     * @param wallDistanceMeters camera-to-wall distance along the view axis.
     * @param focalLengthPx the intrinsics' focal length, in pixels, for the frame being matched.
     * @param poseErrMm the measured recent pose error in millimetres, or **negative when not
     *   measured** — the `DriftCostProbe` convention. Not-measured contributes no drift term, which
     *   is the honest reading: an unmeasured error is not a zero error, but it is also not a licence
     *   to widen the search by an invented amount.
     *
     * Returns a value clamped to `[MIN_PX, MAX_PX]`. Degenerate geometry — a non-positive distance
     * or focal length, or a design with no extent — yields [MAX_PX] rather than [MIN_PX]: when the
     * estimate cannot be computed, the failure that matters is the tight one. A too-wide search
     * costs precision, which the ratio test still filters; a too-tight search silently returns no
     * candidates at all and reads downstream as "the wall does not corroborate the design".
     */
    fun pixels(
        rho: Float,
        designHalfWMeters: Float,
        designHalfHMeters: Float,
        wallDistanceMeters: Float,
        focalLengthPx: Float,
        poseErrMm: Float,
        errGain: Float = ERR_GAIN,
        minPx: Float = MIN_PX,
        maxPx: Float = MAX_PX,
    ): Float {
        val halfExtent = designExtentScalar(designHalfWMeters, designHalfHMeters)
        if (halfExtent <= 0f || wallDistanceMeters <= 0f || focalLengthPx <= 0f || rho <= 0f) {
            return maxPx
        }
        if (!halfExtent.isFinite() || !wallDistanceMeters.isFinite() || !focalLengthPx.isFinite()) {
            return maxPx
        }

        // Metres-to-pixels at the wall: a length L metres at distance d subtends L * f / d pixels.
        val pxPerMeter = focalLengthPx / wallDistanceMeters

        val scaleTermPx = rho * halfExtent * pxPerMeter
        // Negative errMm is "not measured" (DriftCostProbe's sentinel), not zero error.
        val driftTermPx =
            if (poseErrMm >= 0f && poseErrMm.isFinite()) errGain * (poseErrMm / 1000f) * pxPerMeter
            else 0f

        return (scaleTermPx + driftTermPx).coerceIn(minPx, maxPx)
    }

    /**
     * The single length that stands in for an anisotropic design.
     *
     * Geometric mean, not max or min. The radius is a **search bound**, not a metric: it says how
     * far a prediction might be wrong, and that error is isotropic in the image regardless of the
     * artwork's aspect ratio. Taking the max would widen the search on a long thin design for no
     * reason connected to the pose; taking the min would tighten it, which is the failure mode this
     * phase's risk section names. The geometric mean is the scale-free middle, and it degrades to
     * the side length on a square design.
     */
    fun designExtentScalar(halfW: Float, halfH: Float): Float =
        if (halfW <= 0f || halfH <= 0f) 0f else sqrt(halfW * halfH)

    /**
     * Pre-registered priors from `PARAMETERS.md` §5. None is measured; E6/E7/E11 set them.
     *
     * `RHO` is the paper's worked example. `MIN_PX` is where a radius disappears into keypoint
     * localization noise; `MAX_PX` is where "local" stops meaning anything and the argument for
     * loosening the ratio collapses with it. `ERR_GAIN` at 1.0 is the neutral prior — the drift term
     * contributes exactly the measured error, neither damped nor amplified.
     */
    const val RHO = 0.05f
    const val MIN_PX = 4f
    const val MAX_PX = 120f
    const val ERR_GAIN = 1.0f
}
