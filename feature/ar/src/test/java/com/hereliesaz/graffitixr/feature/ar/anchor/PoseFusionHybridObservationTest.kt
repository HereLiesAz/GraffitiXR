package com.hereliesaz.graffitixr.feature.ar.anchor

import com.hereliesaz.graffitixr.common.model.FusionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseFusionHybridObservationTest {

    /** Feeds one hybrid observation; backbone does not move between solve and render. */
    private fun PoseFusion.observe(
        backbone: FloatArray,
        corrected: FloatArray,
        ts: Long,
        confidence: Float = 0.95f,
        inliers: Int = 30,
    ) = currentAnchorFromHybridObservation(
        currentBackbone = backbone,
        backboneAtObservation = backbone,
        correctedAnchorAtObservation = corrected,
        observationTimestampNs = ts,
        confidence = confidence,
        inliers = inliers,
    )

    @Test
    fun `hybrid correction is stored anchor locally and survives ARCore world rebase`() {
        val fusion = PoseFusion()
        val solveBackbone = translated(0f, 0f, -2.3f)
        val physicalAnchor = translated(0f, 0f, -2.0f)

        // 0.3 m exceeds COLD_SNAP_DIST_M, so it needs three agreeing observations.
        fusion.observe(solveBackbone, physicalAnchor, 100L)
        fusion.observe(solveBackbone, physicalAnchor, 200L)
        val first = fusion.observe(solveBackbone, physicalAnchor, 300L)
        assertEquals(-2.0f, first[14], 1e-4f)

        // Simulate an ARCore global world rewrite G = +5m X. Both the live backbone and the physical
        // anchor move by G numerically; the standing local correction must remain +0.3m Z.
        val rebasedBackbone = translated(5f, 0f, -2.3f)
        val held = fusion.currentAnchorFromHybridObservation(
            currentBackbone = rebasedBackbone,
            backboneAtObservation = solveBackbone,
            correctedAnchorAtObservation = physicalAnchor,
            observationTimestampNs = 300L, // same observation -> HOLD
            confidence = 0.95f,
            inliers = 30,
        )
        assertEquals(5f, held[12], 1e-4f)
        assertEquals(-2f, held[14], 1e-4f)
    }

    @Test
    fun `weak hybrid observation cannot create a correction`() {
        val fusion = PoseFusion()
        val backbone = translated(0f,0f,-2.3f)
        val result = fusion.observe(backbone, translated(0f,0f,-2f), 10L, confidence = 0.2f)
        assertEquals(-2.3f, result[14], 1e-4f)
    }

    @Test
    fun `a single large observation is held, not snapped`() {
        val fusion = PoseFusion()
        val backbone = translated(0f, 0f, -2.3f)
        val out = fusion.observe(backbone, translated(0f, 0f, -2.0f), 100L)
        assertEquals(-2.3f, out[14], 1e-4f)
        assertEquals(FusionState.AWAITING_AGREEMENT, fusion.diagnostics().state)
        assertEquals(1, fusion.hybridAgreementCount())
        assertEquals(0, fusion.diagnostics().snapsAccepted)
    }

    @Test
    fun `two agreeing observations are still not enough, the third snaps`() {
        val fusion = PoseFusion()
        val backbone = translated(0f, 0f, -2.3f)
        assertEquals(-2.3f, fusion.observe(backbone, translated(0f, 0f, -2.0f), 100L)[14], 1e-4f)
        // 1 cm apart: within the 5 cm agreement tolerance.
        assertEquals(-2.3f, fusion.observe(backbone, translated(0f, 0.01f, -2.0f), 200L)[14], 1e-4f)
        assertEquals(2, fusion.hybridAgreementCount())
        val snapped = fusion.observe(backbone, translated(0f, 0f, -2.0f), 300L)
        assertEquals(-2.0f, snapped[14], 1e-4f)
        assertEquals(FusionState.COLD_SNAP, fusion.diagnostics().state)
        assertEquals(-1, fusion.hybridAgreementCount())
    }

    @Test
    fun `a disagreeing observation restarts the count`() {
        val fusion = PoseFusion()
        val backbone = translated(0f, 0f, -2.3f)
        fusion.observe(backbone, translated(0f, 0f, -2.0f), 100L)
        fusion.observe(backbone, translated(0f, 0f, -2.0f), 200L)
        // 0.4 m from the pending candidate: a different answer, so the evidence starts over.
        val out = fusion.observe(backbone, translated(0.4f, 0f, -2.0f), 300L)
        assertEquals(-2.3f, out[14], 1e-4f)
        assertEquals(1, fusion.hybridAgreementCount())
    }

    @Test
    fun `agreement older than the window does not count`() {
        val fusion = PoseFusion()
        val backbone = translated(0f, 0f, -2.3f)
        fusion.observe(backbone, translated(0f, 0f, -2.0f), 1_000_000_000L)
        fusion.observe(backbone, translated(0f, 0f, -2.0f), 1_100_000_000L)
        // 1.6 s after the first: past HYBRID_AGREEMENT_WINDOW_NS (1.5 s), so this is a fresh start.
        val out = fusion.observe(backbone, translated(0f, 0f, -2.0f), 2_600_000_000L)
        assertEquals(-2.3f, out[14], 1e-4f)
        assertEquals(1, fusion.hybridAgreementCount())
    }

    @Test
    fun `a small correction still applies on one observation`() {
        val fusion = PoseFusion()
        val backbone = translated(0f, 0f, -2.3f)
        // 5 cm: under COLD_SNAP_DIST_M; first lock, high confidence -> snaps immediately.
        val out = fusion.observe(backbone, translated(0f, 0f, -2.25f), 100L)
        assertEquals(-2.25f, out[14], 1e-4f)
        assertEquals(FusionState.COLD_SNAP, fusion.diagnostics().state)
    }

    @Test
    fun `reset clears pending agreement`() {
        val fusion = PoseFusion()
        val backbone = translated(0f, 0f, -2.3f)
        fusion.observe(backbone, translated(0f, 0f, -2.0f), 100L)
        fusion.observe(backbone, translated(0f, 0f, -2.0f), 200L)
        fusion.reset()
        assertEquals(-1, fusion.hybridAgreementCount())
        val out = fusion.observe(backbone, translated(0f, 0f, -2.0f), 300L)
        assertEquals(-2.3f, out[14], 1e-4f)
        assertEquals(1, fusion.hybridAgreementCount())
    }

    @Test
    fun `holding keeps reporting the pending agreement`() {
        val fusion = PoseFusion()
        val backbone = translated(0f, 0f, -2.3f)
        fusion.observe(backbone, translated(0f, 0f, -2.25f), 100L) // small: establishes a correction
        fusion.observe(backbone, translated(0f, 0f, -1.9f), 200L)  // 0.35 m from drawn: pending
        fusion.holdCurrentAnchor(backbone)
        assertEquals(FusionState.AWAITING_AGREEMENT, fusion.diagnostics().state)
    }

    @Test
    fun `re-presenting the pending observation on later frames keeps AWAITING`() {
        val fusion = PoseFusion()
        val backbone = translated(0f, 0f, -2.3f)
        fusion.observe(backbone, translated(0f, 0f, -2.25f), 100L) // small: standing correction
        fusion.observe(backbone, translated(0f, 0f, -1.9f), 200L)  // large: pending
        fusion.observe(backbone, translated(0f, 0f, -1.9f), 200L)  // same ts, next render frame
        assertEquals(FusionState.AWAITING_AGREEMENT, fusion.diagnostics().state)
        assertTrue(fusion.isAwaitingHybridAgreement())
    }

    @Test
    fun `a weak observation during a pending agreement is refused, not counted`() {
        val fusion = PoseFusion()
        val backbone = translated(0f, 0f, -2.3f)
        fusion.observe(backbone, translated(0f, 0f, -2.25f), 100L)
        fusion.observe(backbone, translated(0f, 0f, -1.9f), 200L)
        fusion.observe(backbone, translated(0f, 0f, -1.9f), 300L, confidence = 0.2f)
        assertEquals(FusionState.RELOCK_REFUSED, fusion.diagnostics().state)
        assertFalse(fusion.isAwaitingHybridAgreement())
    }

    @Test
    fun `a weak observation while the first large move is pending is not reported as awaiting`() {
        val fusion = PoseFusion()
        val backbone = translated(0f, 0f, -2.3f)
        fusion.observe(backbone, translated(0f, 0f, -2.0f), 100L)                 // large, pending, no correction
        fusion.observe(backbone, translated(0f, 0f, -2.0f), 200L, confidence = 0.2f)
        assertFalse(fusion.isAwaitingHybridAgreement())
        assertEquals(FusionState.WAITING_FOR_LOCK, fusion.diagnostics().state)
    }

    @Test
    fun `a backward clock jump discards pending agreement`() {
        val fusion = PoseFusion()
        val backbone = translated(0f, 0f, -2.3f)
        fusion.observe(backbone, translated(0f, 0f, -2.0f), 5_000_000_000L)
        fusion.observe(backbone, translated(0f, 0f, -2.0f), 5_100_000_000L)
        // New ARCore clock epoch: timestamps restart lower. One new observation must not complete
        // agreement with two from the old epoch.
        val out = fusion.observe(backbone, translated(0f, 0f, -2.0f), 1_000L)
        assertEquals(-2.3f, out[14], 1e-4f)
        assertEquals(1, fusion.hybridAgreementCount())
    }

    private fun translated(x: Float, y: Float, z: Float) = floatArrayOf(
        1f,0f,0f,0f,
        0f,1f,0f,0f,
        0f,0f,1f,0f,
        x,y,z,1f,
    )
}
