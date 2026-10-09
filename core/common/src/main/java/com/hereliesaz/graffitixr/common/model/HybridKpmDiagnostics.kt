package com.hereliesaz.graffitixr.common.model

/**
 * What the hybrid (ARCore-primary, SphereSLAM/KPM-sidecar) correction path decided about the most
 * recent KPM observation, and the evidence it decided on.
 *
 * [FusionDiagnostics] says what `PoseFusion` did; this says why a KPM observation did or did not
 * reach it. The artist-visible symptom of every non-[HybridKpmOutcome.ACCEPTED] outcome is the
 * same — the overlay rides the ARCore backbone — so the outcome alone is the diagnosis.
 *
 * Every numeric field is `-1` when not computed for this outcome (an early gate returns before
 * later quantities exist), matching the sentinel convention of the other diagnostics records.
 */
data class HybridKpmDiagnostics(
    val outcome: HybridKpmOutcome = HybridKpmOutcome.NOT_SAMPLED,
    /** KPM image timestamp of the observation this record describes, or -1. */
    val observationTimestampNs: Long = -1L,
    /** Render-frame time minus observation time, in milliseconds, or -1. */
    val ageMs: Float = -1f,
    val inliers: Int = -1,
    val reprojectionPx: Float = -1f,
    /**
     * Magnitude of the anchor-local correction this observation implies — how far ARCore's artwork
     * anchor has drifted from where the KPM page says it is — in millimetres, or -1.
     */
    val correctionMm: Float = -1f,
    /** Rotation magnitude of the same correction in degrees, or -1. */
    val correctionDeg: Float = -1f,
    /** Agreeing large-correction observations collected so far, or -1 when no agreement is pending. */
    val agreeingObservations: Int = -1,
    /** Agreeing observations a large correction needs before it may snap, or -1. */
    val requiredAgreement: Int = -1,
) {
    /** One-line overlay/report rendering. */
    fun summary(): String = buildString {
        append(outcome.name)
        if (ageMs >= 0f) append(" age=").append(ageMs.toInt()).append("ms")
        if (inliers >= 0) append(" in=").append(inliers)
        if (reprojectionPx >= 0f) append(" err=").append(String.format(java.util.Locale.US, "%.1f", reprojectionPx)).append("px")
        if (correctionMm >= 0f) {
            append(" Δ=").append(correctionMm.toInt()).append("mm/")
            append(String.format(java.util.Locale.US, "%.1f", correctionDeg)).append("°")
        }
        if (agreeingObservations >= 0) append(" agree=").append(agreeingObservations).append('/').append(requiredAgreement)
    }
}

/**
 * Outcome of one KPM observation. Not persisted by ordinal anywhere; order follows gate order.
 */
enum class HybridKpmOutcome {
    /** No observation has been evaluated yet (or the hybrid path is not armed). */
    NOT_SAMPLED,
    /** The page is not physically metric, or the page↔artwork relation is not frozen yet. */
    NON_METRIC,
    /** Older than the pairing window, from the future, or the clock is missing. */
    STALE,
    TOO_FEW_INLIERS,
    BAD_REPROJECTION,
    NON_FINITE,
    /** No ARCore pose sample within the timestamp pairing window. */
    NO_POSE_PAIR,
    /** The implied correction exceeds the hard plausibility ceiling; refused outright. */
    CORRECTION_TOO_LARGE,
    /** Passed every gate but implies a large jump; held until repeated observations agree. */
    AWAITING_AGREEMENT,
    /** Handed to `PoseFusion`. */
    ACCEPTED,
}
