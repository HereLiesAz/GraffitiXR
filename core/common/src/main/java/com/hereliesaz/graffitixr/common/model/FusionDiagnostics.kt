package com.hereliesaz.graffitixr.common.model

/**
 * What pose fusion is actually doing right now, and — more usefully — what it is **not** doing and
 * why.
 *
 * Every other diagnostic in this project reports a measurement. This one reports a *decision*,
 * because the failures that have cost the most time here were never wrong numbers: they were stages
 * that silently did not run. Phase 2's partition was inert on every first capture for weeks. The
 * self-grow flag shipped ON while its own comment said OFF. `0.9` added a null check that turns
 * fusion off entirely for a pre-Phase-2 fingerprint — correct, and completely invisible from the
 * outside, which is exactly the class of thing this exists to end.
 *
 * The artist-visible symptom for most of [FusionState] is identical: the overlay sits on the ARCore
 * backbone and drifts. This says which of six quite different causes produced it.
 */
data class FusionDiagnostics(
    val state: FusionState = FusionState.NOT_SAMPLED,
    /**
     * Blend rate the last accepted relock used, or -1 when the last event was not a blend.
     *
     * `BASE_ALPHA · inlierRatio · effConf`. Near zero means corrections are being accepted and then
     * almost entirely ignored — which looks like drift, not like a rejection, and is the reading
     * that separates "not relocalizing" from "relocalizing and barely acting on it".
     */
    val lastAlpha: Float = -1f,
    /** Inlier ratio of the last relocalization the fusion saw, or -1 when none has arrived. */
    val lastInlierRatio: Float = -1f,
    /**
     * How far the standing correction currently displaces the overlay, in millimetres, or -1 when
     * there is no correction.
     *
     * The single most diagnostic number here. A correction of a few millimetres is the system
     * working. One of several hundred is either a genuine relock after real drift or a composition
     * error of the kind `0.9` fixed — and the two are told apart by whether it *stays* large.
     */
    val correctionMm: Float = -1f,
    /** Rotation magnitude of the standing correction in degrees, or -1 when there is none. */
    val correctionDeg: Float = -1f,
    /** Relocalizations accepted since the session started. 0 with a healthy reloc row is the alarm. */
    val snapsAccepted: Int = -1,
    /**
     * Relocalizations REJECTED for a low inlier ratio since the session started.
     *
     * Counted separately from the reloc diagnostics' own rejects because these got all the way to
     * fusion and were thrown away *here*, by `MIN_INLIER_RATIO`. A high count beside a healthy
     * `Reloc` row means the relocalizer is working and fusion does not trust it, which is a
     * different fix from either half failing alone.
     */
    val snapsRejected: Int = -1,
)

/**
 * Why the overlay is or is not being corrected. Ordered roughly by how early the gate sits, so a
 * later value means fusion got further.
 */
enum class FusionState {
    /** No tick has reported yet. Distinct from every real state below. */
    NOT_SAMPLED,

    /** The fusion flag is off — the eval harness A/B, or a deliberate disable. */
    DISABLED,

    /** No anchor established yet: nothing to correct. The scanning state, and not a fault. */
    NO_ANCHOR,

    /**
     * `IMPLEMENTATION.md` **0.9** — the fingerprint carries no `captureAnchorCam`, so the correction
     * cannot be composed at all and fusion is skipped.
     *
     * This is a **pre-Phase-2 project**, and it is the state most likely to be mistaken for a bug:
     * everything else looks healthy, relocalization may even be locking, and the overlay still
     * drifts. The fix is to re-create the target. Correcting anyway would mean composing a
     * CV-convention PnP result against a world-frame anchor, which `PAPER.md` §8.3 says is worse
     * than not correcting.
     */
    NO_CAPTURE_POSE,

    /** Fusion is live but no relocalization has ever been accepted; drawing the raw backbone. */
    WAITING_FOR_LOCK,

    /** The last accepted relock was a hard snap: first lock, or one that diverged past the cold thresholds. */
    COLD_SNAP,

    /** The last accepted relock was eased in at [FusionDiagnostics.lastAlpha]. */
    BLENDING,

    /**
     * A correction is standing and being re-applied, but no new relocalization arrived this tick.
     *
     * The healthy steady state between locks — and also what a *stale* correction looks like, which
     * is why [FusionDiagnostics.snapsAccepted] and the reloc row have to be read beside it.
     */
    HOLDING,
}
