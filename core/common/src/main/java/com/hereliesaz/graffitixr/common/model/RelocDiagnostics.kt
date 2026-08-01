package com.hereliesaz.graffitixr.common.model

/**
 * Why the last relocalization attempt did not publish a pose, and how far it got.
 *
 * Every one of these failures used to be silent. The only published counters updated on SUCCESS, so a
 * relocalizer that had never locked once was indistinguishable from an idle one — all zeros, forever,
 * with no way to tell whether the fingerprint was missing, the frame had no texture, or PnP was
 * solving and falling one inlier short.
 *
 * Lives in `core:common` rather than next to `SlamManager` so both the AR UI state and the native
 * bridge can name the same type.
 */
data class RelocDiagnostics(
    val reject: RelocReject = RelocReject.UNKNOWN,
    /** Correspondences the last attempt built. PnP needs 8. */
    val matches: Int = 0,
    /** RANSAC inliers the last attempt found. Publishing needs 6; PoseFusion trusts a ratio ≥ 0.5. */
    val inliers: Int = 0,
    /**
     * Features detected in the live frame before any matching. Separates "this frame has no texture
     * to work with" (dark, blurred, blank wall) from "plenty of texture, none of it the registered
     * wall" (aimed somewhere else) — a low match count means opposite things in those two cases.
     */
    val detected: Int = 0,
    /**
     * Obliquity (degrees between the wall normal and the optical axis) measured by the plane-guided
     * rectification pass, or -1 when that pass wasn't eligible — no capture view stored, ARCore not
     * tracking, or too few fingerprint points. The pass only warps above 25°.
     */
    val obliquityDeg: Int = -1,
    /** Correspondences the rectification pass contributed on top of the plain and scaled passes. */
    val rectifiedCorrespondences: Int = 0,
    /**
     * Stored fingerprint points in `F_out` — the backbone the reloc PnP is allowed to solve against
     * (`PAPER.md` §5.3) — or -1 when no attempt has got far enough to look.
     *
     * An unpartitioned (legacy) fingerprint reports its full point count, because zero-length
     * regions means all-backbone. **Zero is a real reading**: it says the artwork covers the whole
     * visible wall, so there is nothing left to bootstrap a pose from. That is the limit of the
     * domain of applicability the paper names, and the state `ArUiState.backboneTooSmall` warns
     * about — which is exactly why zero cannot also mean "not measured", and why this follows
     * [obliquityDeg]'s -1 precedent.
     */
    val backboneFeatures: Int = -1,
    /**
     * Correspondences built against `F_out` after the partition filter, or -1 when not measured.
     *
     * Counted apart from [matches], never in place of it. The same shortfall means different
     * things: a low total is "the frame is not looking at the registered wall", a low backbone
     * under a healthy total is "everything it can see is under the artwork", and those call for
     * opposite advice. The gap between the two is the map-reloc path's contribution, which Φ never
     * classified.
     */
    val backboneMatches: Int = -1,
    /** RANSAC inliers that came from `F_out` points, or -1 when PnP produced no inlier set. */
    val backboneInliers: Int = -1,
    /**
     * `IMPLEMENTATION.md` **4.6** — design features the current pose predicts are visible in the
     * frame, or -1 when no *gated* corroboration attempt has run.
     *
     * The global fallback match deliberately does not fill this in. It measures the whole design
     * including the parts behind the camera, so reporting its count here would put two different
     * quantities in one column and make a Phase-4 run look comparable to a pre-Phase-4 one.
     *
     * **Zero is a real reading** — the artist is looking away from the design — and it is the state
     * `4.8` exists to keep distinct from a zero [corrobMatched].
     */
    val corrobPredicted: Int = -1,
    /**
     * How many of [corrobPredicted] the wall answered for, or -1 when no gated attempt has run.
     *
     * `corrobMatched / corrobPredicted` is the corroboration confidence `PoseFusion` scales by;
     * `corrobMatched` against the whole design is *not* — painting progress has its own, cumulative
     * denominator, and conflating the two is the defect Phase 5 exists to undo.
     */
    val corrobMatched: Int = -1,
) {
    /** Inlier ratio of the last attempt, or 0 when it produced no correspondences. */
    val inlierRatio: Float get() = if (matches > 0) inliers.toFloat() / matches else 0f
}

/**
 * `IMPLEMENTATION.md` **4.6** — the corroboration path's pixel-valued readings.
 *
 * Separate from [RelocDiagnostics] only because that channel is an `int[]`: rounding a sub-pixel
 * radius to an integer to share it would destroy the reading at exactly the tight radii Phase 4 is
 * trying to measure.
 *
 * @param searchRadiusPx the radius the last gated corroboration attempt used, or -1 when none has
 *   run. Derived from `ρ`, the design's on-screen scale and the measured drift — see `SearchRadius`.
 * @param relocReprojPx the mean reprojection error over the last lock's PnP inliers, or -1 when not
 *   measured. **Zero cannot be the sentinel here**: a perfectly tracking pose really does report a
 *   near-zero residual, and that is the reading this column exists to show.
 */
data class CorroborationDiagnostics(
    val searchRadiusPx: Float = -1f,
    val relocReprojPx: Float = -1f,
)

/**
 * Mirrors `MobileGS::RelocReject`, ordinal for ordinal — the native side reports an int. Ordered by
 * how early the gate sits in the pipeline, so a larger value means the attempt got further.
 */
enum class RelocReject {
    /** Published a pose. */
    OK,
    /** No wall fingerprint yet, or one carrying descriptors but no 3D points. */
    NO_FINGERPRINT,
    /** Relocalization switched off. */
    DISABLED,
    /** Nothing detected in the live frame, or a descriptor type that can't compare. */
    NO_FEATURES,
    /** Fewer than 8 correspondences survived the Lowe ratio test. */
    FEW_MATCHES,
    /** solvePnPRansac found no consistent pose. */
    PNP_FAILED,
    /** PnP solved but fewer than 6 inliers agreed. */
    FEW_INLIERS,
    /** The native side reported a code this build doesn't know. */
    UNKNOWN,
}
