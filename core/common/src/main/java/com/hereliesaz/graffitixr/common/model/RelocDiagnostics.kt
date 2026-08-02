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
    /**
     * `IMPLEMENTATION.md` **4.6** — predicted-visible features the gated match **refused to test**,
     * because their neighbourhood held a single candidate and Lowe's ratio has nothing to divide
     * by. -1 when no gated attempt has run.
     *
     * This is the number that says whether the search radius is usable. A skip deflates
     * [corrobMatched] without touching [corrobPredicted], so a radius that is too tight does not
     * announce itself — it announces itself as a wall that has stopped corroborating, which looks
     * exactly like an unpainted one. And the deflation is not harmless: lower confidence means a
     * smaller correction blend in `PoseFusion`, so a too-tight radius trades false snapping for
     * accumulated drift, which is the thing corroboration exists to prevent.
     */
    val corrobLoneSkips: Int = -1,
    /**
     * `IMPLEMENTATION.md` **4.5** — why the spatially-constrained corroboration match did or did not
     * run. See [CorrobGate].
     *
     * Every failing precondition produces the same observable otherwise: the three counts above read
     * -1 and the overlay shows a dash. They call for completely different responses — place the
     * design, aim at the registered wall, re-create the target, file a bug — so collapsing them into
     * one absence throws away the only information that distinguishes them.
     */
    val corrobGate: CorrobGate = CorrobGate.NOT_RUN,
    /**
     * `IMPLEMENTATION.md` **Phase 3** — what self-grow did, or which gate declined. See [GrowOutcome].
     *
     * Phase 3 is otherwise invisible: self-grow is default-off, hard-gated, and every one of its
     * refusals is silent by construction, so "the map is not growing" has eight causes that look
     * identical — one of which is simply the switch being off, as intended.
     */
    val growOutcome: GrowOutcome = GrowOutcome.NOT_RUN,
) {
    /** Inlier ratio of the last attempt, or 0 when it produced no correspondences. */
    val inlierRatio: Float get() = if (matches > 0) inliers.toFloat() / matches else 0f
}

/**
 * Mirrors `MobileGS::CorrobGate`, ordinal for ordinal — the native side reports an int.
 *
 * Like `RelocReject`, this is a **wire format** with no compiler checking either side, so the order
 * is load-bearing. `UNKNOWN` absorbs any value a newer `.so` reports that this build does not know,
 * rather than throwing or silently reading as the first entry.
 */
enum class CorrobGate {
    /** Ran the gated match. */
    GATED,
    /** No design registered — the artwork guide has never been pushed for this project. */
    NO_ARTWORK,
    /** No design placement: Phase 4 is inactive here, and the match fell back to a global search. */
    NO_PLACEMENT,
    /** Relocalization did not lock this frame, so there is no pose to predict from. */
    NO_POSE,
    /** The artwork's 2D positions do not index its descriptors 1:1 — a bug, not a state. */
    BAD_2D,
    /** The fingerprint carries no intrinsics to project through. */
    NO_INTRINSICS,
    /** No corroboration attempt reached the decision at all. */
    NOT_RUN,
    /** A code this build does not know — a newer native library. */
    UNKNOWN,
}

/**
 * Mirrors `MobileGS::GrowOutcome`, ordinal for ordinal. Same wire-format caveat as [CorrobGate].
 */
enum class GrowOutcome {
    /** The promotion block was not reached this attempt. */
    NOT_RUN,
    /** Self-grow is switched off. **The default**, and not a fault — Phase 3 ships gated. */
    DISABLED,
    /** Nothing in the frame corroborated the design, so there was nothing to promote. */
    NO_CANDIDATES,
    /** No fresh relock since the last promotion; the pose would be stale. */
    STALE_SEQ,
    /** The promotion gate refused — inlier spread or match quality. Tuning, and E5 sets it. */
    UNTRUSTED,
    /** Intrinsics, wall size or the plane fit are degenerate. */
    NO_GEOMETRY,
    /** Every candidate back-projected behind the camera or deduplicated against an existing mark. */
    NO_NEW_POINTS,
    /** The wall is at its hard ceiling. Not a fault. */
    AT_CAP,
    /** Marks were written into the map. */
    PROMOTED,
    /** A code this build does not know — a newer native library. */
    UNKNOWN,
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
    /**
     * `IMPLEMENTATION.md` **3.3** — the last lock's inlier bounding box as a fraction of frame area,
     * or -1 when not measured.
     *
     * The gate this feeds gates an **irreversible** map mutation, and its refusals are otherwise
     * invisible: self-grow declining because the inliers were clustered looks exactly like self-grow
     * not having fired at all. E5 has to tell those apart to say whether the term is too strict.
     *
     * **Zero is a real reading** and the worst one — every inlier on a single pixel, a pose whose
     * rotation is unconstrained — so it cannot double as the sentinel.
     */
    val inlierSpread: Float = -1f,
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
