package com.hereliesaz.graffitixr.feature.ar.anchor

import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dropout policy in [AnchorOrchestrator.getConsensusMatrix]: hold the last good world matrix
 * while nothing is tracking, and only write identity before any consensus has ever been computed.
 * Writing identity on every dropped frame is what would snap the overlay to the world origin.
 *
 * Assertable only since the identity fallback stopped going through `android.opengl.Matrix` — under
 * this module's `unitTests.isReturnDefaultValues = true`, `setIdentityM` leaves the array untouched,
 * so the fallback used to be indistinguishable from doing nothing.
 */
class AnchorOrchestratorDropoutTest {

    private val identity = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)

    @Test fun `writes identity when nothing has ever tracked`() {
        // Pre-filled with junk: a no-op fallback would leave it there.
        val out = FloatArray(16) { 7f }
        AnchorOrchestrator().getConsensusMatrix(out)
        assertArrayEquals(identity, out, 1e-6f)
    }

    @Test fun `holds the last good matrix through a tracking dropout`() {
        val anchor = mockk<Anchor>(relaxed = true)
        every { anchor.pose } returns Pose(floatArrayOf(1f, 2f, 3f), floatArrayOf(0f, 0f, 0f, 1f))
        every { anchor.trackingState } returns TrackingState.TRACKING

        val orchestrator = AnchorOrchestrator()
        orchestrator.setInitialAnchor(anchor)

        val good = FloatArray(16)
        orchestrator.getConsensusMatrix(good)
        assertEquals(1f, good[12], 1e-4f)
        assertEquals(2f, good[13], 1e-4f)
        assertEquals(3f, good[14], 1e-4f)

        every { anchor.trackingState } returns TrackingState.PAUSED
        val out = FloatArray(16) { 7f }
        orchestrator.getConsensusMatrix(out)
        assertArrayEquals(good, out, 1e-6f)
    }
}
