package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import androidx.camera.core.ImageProxy
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The parts of [HomographyTrackingAnalyzer] testable without a device: the no-Y-plane bail-out and
 * the [ImageProxy.close] resource-safety guarantee. The happy path — an actual
 * [BridgedHomographyTracker] call into SphereSLAM's OpenCV-backed `PlanarTracker` — needs the OpenCV
 * native library and isn't reachable from a plain JVM unit test. The luma packing, rotation,
 * projection, planar tracking and gyro-bridge logic it relies on are SphereSLAM's
 * (`camera.LumaFrameTransform`, `camera.ProjectionMatrix`, `reloc.PlanarTracker`) and are
 * unit-tested in that library.
 */
class HomographyTrackingAnalyzerTest {

    @Test
    fun `a frame with no planes is skipped without crashing, and still closed`() {
        var callbackInvocations = 0
        val analyzer = HomographyTrackingAnalyzer(
            context = mockk<Context>(),
            cameraId = "0",
            tracker = mockk(relaxed = true),
            onFrameTracked = { callbackInvocations++ },
        )

        val imageProxy = mockk<ImageProxy> {
            every { planes } returns emptyArray()
            every { close() } just runs
        }

        analyzer.analyze(imageProxy)

        verify(exactly = 1) { imageProxy.close() }
        assertEquals("onFrameTracked must not fire for a frame that was skipped", 0, callbackInvocations)
    }
}
