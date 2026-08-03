package com.hereliesaz.graffitixr.feature.ar.anchor

import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `primaryAnchorDriftMeters` — the diagnostic added to make the "the overlay is receding" report
 * falsifiable instead of another round of screenshots and a stopwatch.
 *
 * It answers exactly one question: has the ARCore anchor's OWN pose moved since it was established,
 * independent of fusion, relocalization, or the consensus average. A device run went 6.2 -> 16.0 ft
 * in four seconds with fusion off and no fingerprint — nothing downstream of the anchor was even
 * running — traced to the primary anchor being attached to a still-forming ARCore Plane trackable
 * rather than a fixed world pose, so ARCore's own plane refinement dragged it along.
 */
class AnchorOrchestratorDriftTest {

    private fun anchorAt(x: Float, y: Float, z: Float, state: TrackingState = TrackingState.TRACKING): Anchor {
        val a = mockk<Anchor>(relaxed = true)
        every { a.pose } returns Pose(floatArrayOf(x, y, z), floatArrayOf(0f, 0f, 0f, 1f))
        every { a.trackingState } returns state
        return a
    }

    @Test fun `no established anchor reports the not-measured sentinel`() {
        assertEquals(-1f, AnchorOrchestrator().primaryAnchorDriftMeters(), 0f)
    }

    @Test fun `an anchor that has not moved reports zero drift`() {
        val o = AnchorOrchestrator()
        o.setInitialAnchor(anchorAt(1f, 2f, 3f))
        assertEquals(0f, o.primaryAnchorDriftMeters(), 1e-5f)
    }

    /**
     * The mechanism under test: a mock `Anchor` returning a DIFFERENT pose on a later call is
     * exactly what a trackable-attached anchor does as ARCore keeps refining the plane it rides on
     * — the anchor object is the same, its `.pose` is not.
     */
    @Test fun `an anchor whose pose has moved reports the true 3D distance`() {
        val a = mockk<Anchor>(relaxed = true)
        every { a.trackingState } returns TrackingState.TRACKING
        every { a.pose } returns Pose(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f))

        val o = AnchorOrchestrator()
        o.setInitialAnchor(a)

        // 3-4-0 right triangle: distance is exactly 5.
        every { a.pose } returns Pose(floatArrayOf(3f, 4f, 0f), floatArrayOf(0f, 0f, 0f, 1f))
        assertEquals(5f, o.primaryAnchorDriftMeters(), 1e-4f)
    }

    @Test fun `re-establishing resets the drift baseline to the new anchor`() {
        val o = AnchorOrchestrator()
        o.setInitialAnchor(anchorAt(0f, 0f, 0f))
        o.setInitialAnchor(anchorAt(100f, 100f, 100f))
        // If the old baseline survived, this would report ~173m instead of 0.
        assertEquals(0f, o.primaryAnchorDriftMeters(), 1e-4f)
    }

    @Test fun `clear drops the baseline back to not-measured`() {
        val o = AnchorOrchestrator()
        o.setInitialAnchor(anchorAt(1f, 1f, 1f))
        o.clear()
        assertEquals(-1f, o.primaryAnchorDriftMeters(), 0f)
    }

    /** A dropped-tracking anchor cannot report a live drift; -1 is honest, not a stale number. */
    @Test fun `a paused anchor reports not-measured rather than a stale distance`() {
        val a = mockk<Anchor>(relaxed = true)
        every { a.pose } returns Pose(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f))
        every { a.trackingState } returns TrackingState.TRACKING
        val o = AnchorOrchestrator()
        o.setInitialAnchor(a)

        every { a.trackingState } returns TrackingState.PAUSED
        assertEquals(-1f, o.primaryAnchorDriftMeters(), 0f)
    }
}
