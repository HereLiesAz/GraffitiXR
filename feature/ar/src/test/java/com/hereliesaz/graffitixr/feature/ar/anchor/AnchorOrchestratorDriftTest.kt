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
 * and independent support-anchor votes. A global world-frame correction cancels out. Without a
 * tracking support vote the metric is unmeasured (-1), never a fabricated zero.
 */
class AnchorOrchestratorDriftTest {

    private fun pose(x: Float, y: Float, z: Float) =
        Pose(floatArrayOf(x, y, z), floatArrayOf(0f, 0f, 0f, 1f))

    private fun anchorAt(x: Float, y: Float, z: Float, state: TrackingState = TrackingState.TRACKING): Anchor {
        val a = mockk<Anchor>(relaxed = true)
        every { a.pose } returns pose(x, y, z)
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

    @Test fun `single tracking anchor is unmeasured without an independent support vote`() {
        val o = AnchorOrchestrator()
        o.setInitialAnchor(anchorAt(1f, 2f, 3f))
        assertEquals(-1f, o.primaryAnchorDriftMeters(), 0f)
    }

    @Test fun `cross-frame world-coordinate movement alone remains unmeasured without support`() {
        val primary = mockk<Anchor>(relaxed = true)
        every { primary.trackingState } returns TrackingState.TRACKING
        every { primary.pose } returns pose(0f, 0f, 0f)

        val o = AnchorOrchestrator()
        o.setInitialAnchor(primary)

        // ARCore is allowed to rewrite this world-space number after Session.update(). With no
        // independent same-frame reference, calling this 5 m of "drift" OR 0 m would both be fiction.
        every { primary.pose } returns pose(3f, 4f, 0f)
        assertEquals(-1f, o.primaryAnchorDriftMeters(), 0f)
    }

    @Test fun `support created after world-frame rewrite uses current primary pose not establishment pose`() {
        val primary = mockk<Anchor>(relaxed = true)
        every { primary.trackingState } returns TrackingState.TRACKING
        every { primary.pose } returns pose(0f, 0f, 0f)

        val o = AnchorOrchestrator()
        o.setInitialAnchor(primary)

        // Between target establishment and support creation ARCore rewrites the world basis +10 m.
        // The physical primary/support relationship is still one metre. The old implementation kept
        // the establishment-time primary world pose (0), so it solved support⁻¹ × oldPrimary as -11
        // instead of the correct same-frame -1 and immediately manufactured ten metres of conflict.
        every { primary.pose } returns pose(10f, 0f, 0f)
        val support = anchorAt(11f, 0f, 0f)
        addSupport(o, pose(11f, 0f, 0f), support)

        assertEquals(0f, o.primaryAnchorDriftMeters(), 1e-4f)
    }

    @Test fun `later rigid world-frame correction applied to primary and support cancels out`() {
        val primary = mockk<Anchor>(relaxed = true)
        val support = mockk<Anchor>(relaxed = true)
        every { primary.trackingState } returns TrackingState.TRACKING
        every { support.trackingState } returns TrackingState.TRACKING
        every { primary.pose } returns pose(10f, 0f, 0f)
        every { support.pose } returns pose(11f, 0f, 0f)

        val o = AnchorOrchestrator()
        o.setInitialAnchor(primary)
        addSupport(o, pose(11f, 0f, 0f), support)

        // Both current world coordinates move +5 m later. Because the stored offset is relative, both
        // anchors still vote for the same physical artwork location in the new current frame.
        every { primary.pose } returns pose(15f, 0f, 0f)
        every { support.pose } returns pose(16f, 0f, 0f)
        assertEquals(0f, o.primaryAnchorDriftMeters(), 1e-4f)
    }

    @Test fun `primary disagreement with support consensus is reported in current-frame metres`() {
        val primary = anchorAt(0f, 0f, 0f)
        val support = mockk<Anchor>(relaxed = true)
        every { support.trackingState } returns TrackingState.TRACKING
        every { support.pose } returns pose(1f, 0f, 0f)

        val o = AnchorOrchestrator()
        o.setInitialAnchor(primary)
        addSupport(o, pose(1f, 0f, 0f), support)

        // Support was created 1 m away, so its offset is -1 and both initially vote for x=0.
        // If only that support's current pose later diverges to x=4, its artwork vote becomes x=3.
        every { support.pose } returns pose(4f, 0f, 0f)
        assertEquals(3f, o.primaryAnchorDriftMeters(), 1e-4f)
    }

    @Test fun `paused support makes disagreement unmeasured rather than zero`() {
        val primary = anchorAt(0f, 0f, 0f)
        val support = mockk<Anchor>(relaxed = true)
        every { support.trackingState } returns TrackingState.TRACKING
        every { support.pose } returns pose(1f, 0f, 0f)

        val o = AnchorOrchestrator()
        o.setInitialAnchor(primary)
        addSupport(o, pose(1f, 0f, 0f), support)

        every { support.trackingState } returns TrackingState.PAUSED
        assertEquals(-1f, o.primaryAnchorDriftMeters(), 0f)
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
        every { a.pose } returns pose(0f, 0f, 0f)
        every { a.trackingState } returns TrackingState.TRACKING
        val o = AnchorOrchestrator()
        o.setInitialAnchor(a)

        every { a.trackingState } returns TrackingState.PAUSED
        assertEquals(-1f, o.primaryAnchorDriftMeters(), 0f)
    }
}