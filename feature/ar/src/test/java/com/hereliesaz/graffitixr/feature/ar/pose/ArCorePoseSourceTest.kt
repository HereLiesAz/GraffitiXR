package com.hereliesaz.graffitixr.feature.ar.pose

import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArCorePoseSourceTest {

    private fun frame(
        view: FloatArray,
        proj: FloatArray,
        timestamp: Long,
        tracking: TrackingState,
    ): Frame {
        val camera = mockk<Camera>()
        every { camera.trackingState } returns tracking
        every { camera.getViewMatrix(any(), any()) } answers {
            view.copyInto(firstArg(), secondArg())
        }
        every { camera.getProjectionMatrix(any(), any(), any(), any()) } answers {
            proj.copyInto(firstArg(), secondArg())
        }
        val frame = mockk<Frame>()
        every { frame.camera } returns camera
        every { frame.timestamp } returns timestamp
        return frame
    }

    @Test
    fun `sample returns null before any frame is bound`() {
        val source = ArCorePoseSource()
        assertNull(source.sample(FloatArray(16), FloatArray(16), 0.1f, 100f))
    }

    @Test
    fun `sample fills matrices and reports tracking metadata from the bound frame`() {
        val view = FloatArray(16) { it.toFloat() }
        val proj = FloatArray(16) { (it * 2).toFloat() }
        val source = ArCorePoseSource()
        source.bind(frame(view, proj, timestamp = 42L, tracking = TrackingState.TRACKING))

        val outView = FloatArray(16)
        val outProj = FloatArray(16)
        val meta = source.sample(outView, outProj, 0.1f, 100f)!!

        assertArrayEquals(view, outView, 0f)
        assertArrayEquals(proj, outProj, 0f)
        assertEquals(42L, meta.timestampNs)
        assertTrue(meta.isTracking)
    }

    @Test
    fun `isTracking is false when the camera is not tracking`() {
        val source = ArCorePoseSource()
        source.bind(frame(FloatArray(16), FloatArray(16), timestamp = 1L, tracking = TrackingState.PAUSED))

        val meta = source.sample(FloatArray(16), FloatArray(16), 0.1f, 100f)!!
        assertFalse(meta.isTracking)
    }
}
