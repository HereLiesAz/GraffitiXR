package com.hereliesaz.graffitixr.feature.ar.rendering

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayRotationCorrectionDecisionTest {
    @Test
    fun validCandidateAppliesImmediately() {
        assertEquals(
            OverlayRotationCorrectionDecision.APPLY,
            decideOverlayRotationCorrection(
                valid = true,
                retryFrames = 0,
                maxRetryFrames = 8,
            ),
        )
    }

    @Test
    fun invalidCandidateRetriesWhileBudgetRemains() {
        assertEquals(
            OverlayRotationCorrectionDecision.RETRY,
            decideOverlayRotationCorrection(
                valid = false,
                retryFrames = 7,
                maxRetryFrames = 8,
            ),
        )
    }

    @Test
    fun invalidCandidateIsDiscardedWhenBudgetIsExhausted() {
        assertEquals(
            OverlayRotationCorrectionDecision.DISCARD,
            decideOverlayRotationCorrection(
                valid = false,
                retryFrames = 8,
                maxRetryFrames = 8,
            ),
        )
    }

    @Test
    fun validCandidateStillAppliesAtRetryBoundary() {
        assertEquals(
            OverlayRotationCorrectionDecision.APPLY,
            decideOverlayRotationCorrection(
                valid = true,
                retryFrames = 8,
                maxRetryFrames = 8,
            ),
        )
    }
}
