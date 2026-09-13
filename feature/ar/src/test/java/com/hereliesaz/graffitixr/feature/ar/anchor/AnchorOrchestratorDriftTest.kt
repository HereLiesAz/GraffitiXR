package com.hereliesaz.graffitixr.feature.ar.anchor

import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for the historical `primaryAnchorDriftMeters` diagnostic.
 *
 * The method name survives because ArRenderer/diagnostic reports already expose it, but the old
 * implementation was invalid ARCore math: it compared an Anchor.pose from the current frame with a
 * world-space translation saved from an earlier frame. ARCore explicitly permits the numerical world
 * coordinates of BOTH camera and anchors to move substantially as its world estimate is corrected.
 * Cross-frame world-coordinate delta is therefore not physical drift.
 *
 * The implementation now measures only same-frame relative disagreement between the primary anchor
 * and independent support-anchor votes. A global world-frame correction cancels out.
 */
class AnchorOrchestratorDriftTest {

    private fun anchorAt(x: Float, y: Float, z: Float, state: TrackingState = TrackingState.TRACKING): Anchor {
        val a = mockk<Anchor>(relaxed = true)
        every { a.pose } returns Pose(floatArrayOf(x, y, z), floatArrayOf(0f, 0f, 0f, 1f))
        every { a.trackingState } returns state
        return a
    }

    private fun addSupport(
        orchestrator: AnchorOrchestrator,
        creationPose: Pose,
        liveAnchor: Anchor,
    ) {
        val session = mockk<Session>()
        every { session.createAnchor(creationPose) } returns liveAnchor
        orchestrator.addSupportAnchor(session, creationPose)
    }

    @Test fun `no established anchor reports the not-measured sentinel`() {
        assertEquals(-1f, AnchorOrchestrator().primaryAnchorDriftMeters(), 0f)
    }

    @Test fun `single tracking anchor reports no observed disagreement`() {
        val o = AnchorOrchestrator()
        o.setInitialAnchor(anchorAt(1f, 2f, 3f))
        assertEquals(0f, o.primaryAnchorDriftMeters(), 1e-5f)
    }

    @Test fun `cross-frame world-coordinate movement alone is not reported as physical drift`() {
        val primary = mockk<Anchor>(relaxed = true)
        every { primary.trackingState } returns TrackingState.TRACKING
        every { primary.pose } returns Pose(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f))

        val o = AnchorOrchestrator()
        o.setInitialAnchor(primary)

        // ARCore is allowed to rewrite this world-space number after Session.update(). With no
        // independent same-frame reference, calling this 5 m of "drift" would be fiction.
        every { primary.pose } returns Pose(floatArrayOf(3f, 4f, 0f), floatArrayOf(0f, 0f, 0f, 1f))
        assertEquals(0f, o.primaryAnchorDriftMeters(), 1e-4f)
    }

    @Test fun `rigid world-frame correction applied to primary and support cancels out`() {
        val primary = mockk<Anchor>(relaxed = true)
        every { primary.trackingState } returns TrackingState.TRACKING
        every { primary.pose } returns Pose(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f))

        val support = anchorAt(11f, 0f, 0f)
        val o = AnchorOrchestrator()
        o.setInitialAnchor(primary)
        addSupport(
            o,
            Pose(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f)),
            support,
        )

        // A +10 m rewrite of the current ARCore world frame moves both numerical poses. The support's
        // stored -1 m artwork offset makes both anchors vote for x=10 in THIS frame.
        every { primary.pose } returns Pose(floatArrayOf(10f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f))
        assertEquals(0f, o.primaryAnchorDriftMeters(), 1e-4f)
    }

    @Test fun `primary disagreement with support consensus is reported in current-frame metres`() {
        val primary = anchorAt(0f, 0f, 0f)
        val support = anchorAt(4f, 0f, 0f)
        val o = AnchorOrchestrator()
        o.setInitialAnchor(primary)
        addSupport(
            o,
            Pose(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f)),
            support,
        )

        // Support offset is -1 m, so its artwork vote is x=3 while primary votes x=0.
        assertEquals(3f, o.primaryAnchorDriftMeters(), 1e-4f)
    }

    @Test fun `re-establishing detaches the superseded anchor on the serialized path`() {
        val oldAnchor = anchorAt(0f, 0f, 0f)
        val newAnchor = anchorAt(1f, 1f, 1f)
        val o = AnchorOrchestrator()

        o.setInitialAnchor(oldAnchor)
        o.setInitialAnchor(newAnchor)

        verify(exactly = 1) { oldAnchor.detach() }
        verify(exactly = 0) { newAnchor.detach() }
    }

    @Test fun `clear drops anchor state without touching ARCore native anchor state`() {
        val anchor = anchorAt(1f, 1f, 1f)
        val o = AnchorOrchestrator()
        o.setInitialAnchor(anchor)

        o.clear()

        assertEquals(-1f, o.primaryAnchorDriftMeters(), 0f)
        verify(exactly = 0) { anchor.detach() }
    }

    @Test fun `a paused primary reports not-measured rather than stale disagreement`() {
        val a = mockk<Anchor>(relaxed = true)
        every { a.pose } returns Pose(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f))
        every { a.trackingState } returns TrackingState.TRACKING
        val o = AnchorOrchestrator()
        o.setInitialAnchor(a)

        every { a.trackingState } returns TrackingState.PAUSED
        assertEquals(-1f, o.primaryAnchorDriftMeters(), 0f)
    }
}
