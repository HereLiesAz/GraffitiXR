package com.hereliesaz.graffitixr.feature.ar.rendering

internal enum class OverlayRotationCorrectionDecision {
    APPLY,
    RETRY,
    DISCARD,
}

internal fun decideOverlayRotationCorrection(
    valid: Boolean,
    retryFrames: Int,
    maxRetryFrames: Int,
): OverlayRotationCorrectionDecision {
    require(retryFrames >= 0) { "retryFrames must be non-negative" }
    require(maxRetryFrames >= 0) { "maxRetryFrames must be non-negative" }

    return when {
        valid -> OverlayRotationCorrectionDecision.APPLY
        retryFrames >= maxRetryFrames -> OverlayRotationCorrectionDecision.DISCARD
        else -> OverlayRotationCorrectionDecision.RETRY
    }
}
