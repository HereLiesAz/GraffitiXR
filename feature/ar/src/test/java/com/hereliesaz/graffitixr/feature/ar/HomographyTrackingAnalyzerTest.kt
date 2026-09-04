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
 * The parts of [HomographyTrackingAnalyzer] testable without a device: the underlying-`Image`-is-
 * null bail-out and the [ImageProxy.close] resource-safety guarantee. The happy path — real YUV
 * decode via the native `YuvConverter`, `Bitmap` rotation, an actual [BridgedHomographyTracker]
 * call — needs the compiled `.so` and isn't reachable from a plain JVM unit test; see the sibling
 * pure-math tests ([com.hereliesaz.graffitixr.feature.ar.util.RotationDeltaMathTest],
 * [com.hereliesaz.graffitixr.feature.ar.rendering.ProjectionMatrixTest]) for what IS covered here.
 */
class HomographyTrackingAnalyzerTest {

    @Test
    fun `a frame with no underlying Image is skipped without crashing, and still closed`() {
        var callbackInvocations = 0
        val analyzer = HomographyTrackingAnalyzer(
            context = mockk<Context>(),
            cameraId = "0",
            tracker = mockk(relaxed = true),
            onPoseTracked = { callbackInvocations++ },
        )

        val imageProxy = mockk<ImageProxy> {
            every { image } returns null
            every { close() } just runs
        }

        analyzer.analyze(imageProxy)

        verify(exactly = 1) { imageProxy.close() }
        assertEquals("onPoseTracked must not fire for a frame that was skipped", 0, callbackInvocations)
    }
}
