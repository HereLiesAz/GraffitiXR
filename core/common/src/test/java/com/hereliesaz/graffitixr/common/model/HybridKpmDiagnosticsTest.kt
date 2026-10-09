package com.hereliesaz.graffitixr.common.model

import org.junit.Assert.assertEquals
import org.junit.Test

class HybridKpmDiagnosticsTest {
    @Test
    fun `summary omits unset fields`() {
        assertEquals("NOT_SAMPLED", HybridKpmDiagnostics().summary())
        assertEquals(
            "TOO_FEW_INLIERS age=12ms in=3 err=1.0px",
            HybridKpmDiagnostics(
                outcome = HybridKpmOutcome.TOO_FEW_INLIERS,
                ageMs = 12.7f, inliers = 3, reprojectionPx = 1f,
            ).summary(),
        )
    }

    @Test
    fun `summary renders correction and agreement`() {
        assertEquals(
            "AWAITING_AGREEMENT in=30 Δ=300mm/2.5° agree=2/3",
            HybridKpmDiagnostics(
                outcome = HybridKpmOutcome.AWAITING_AGREEMENT,
                inliers = 30, correctionMm = 300.4f, correctionDeg = 2.5f,
                agreeingObservations = 2, requiredAgreement = 3,
            ).summary(),
        )
    }
}
