package com.hereliesaz.graffitixr.feature.ar.rendering

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayRotationCorrectionPolicyTest {

    @Test fun `valid candidate applies immediately even at retry limit`() {
        assertEquals(
            OverlayRotationCorrectionDecision.APPLY,
            decideOverlayRotationCorrection(valid = true, retryFrames = 15, maxRetryFrames = 15),
        )
    }

    @Test fun `invalid candidate retries while budget remains`() {
        assertEquals(
            OverlayRotationCorrectionDecision.RETRY,
            decideOverlayRotationCorrection(valid = false, retryFrames = 14, maxRetryFrames = 15),
        )
    }

    @Test fun `invalid candidate is discarded when retry budget is exhausted`() {
        assertEquals(
            OverlayRotationCorrectionDecision.DISCARD,
            decideOverlayRotationCorrection(valid = false, retryFrames = 15, maxRetryFrames = 15),
        )
    }
}
