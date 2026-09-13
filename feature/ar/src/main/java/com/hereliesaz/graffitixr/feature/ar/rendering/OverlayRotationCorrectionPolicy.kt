package com.hereliesaz.graffitixr.feature.ar.rendering

internal enum class OverlayRotationCorrectionDecision {
    APPLY,
    RETRY,
    DISCARD,
}

/**
 * Decides what to do with the one-time overlay rotation correction candidate.
 *
 * An invalid candidate never becomes authoritative merely because time passed. While the retry
 * budget remains, wait for a frame whose anchor pose can be validated. Once the budget is exhausted,
 * discard the candidate and keep the renderer's existing correction unchanged — identity on first
 * establishment, which is exactly the raw/live ARCore anchor orientation.
 */
internal fun decideOverlayRotationCorrection(
    valid: Boolean,
    retryFrames: Int,
    maxRetryFrames: Int,
): OverlayRotationCorrectionDecision = when {
    valid -> OverlayRotationCorrectionDecision.APPLY
    retryFrames >= maxRetryFrames -> OverlayRotationCorrectionDecision.DISCARD
    else -> OverlayRotationCorrectionDecision.RETRY
}
