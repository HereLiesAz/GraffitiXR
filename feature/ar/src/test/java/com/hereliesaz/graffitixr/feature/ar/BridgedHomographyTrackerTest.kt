// FILE: feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/BridgedHomographyTrackerTest.kt
package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.graphics.Bitmap
import com.hereliesaz.graffitixr.feature.ar.HomographyArTracker.HomographyPose
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for the bug the audit caught: a bridged pose used to hold the view matrix's
 * TRANSLATION column fixed while only rotating it, which moves the camera centre (`C = -Rᵀt`) even
 * though the phone hasn't actually moved — the opposite of what the bridge exists to do. The fix
 * (see [BridgedHomographyTracker.trackFrame] and [GyroOrientationBridge.cameraRotationDelta]) rotates
 * translation by the SAME delta as rotation, so the camera centre stays fixed under a pure pan/tilt.
 */
class BridgedHomographyTrackerTest {

    /** Row-major 3x3 identity. */
    private val identityRotation = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
    )

    /** Row-major 3x3, 90-degree rotation about Y: `(x,y,z) -> (z,y,-x)`. */
    private val ninetyAboutY = floatArrayOf(
        0f, 0f, 1f,
        0f, 1f, 0f,
        -1f, 0f, 0f,
    )

    private fun viewMatrixOf(rotation: FloatArray, translation: FloatArray): FloatArray {
        val m = FloatArray(16)
        for (row in 0..2) for (col in 0..2) m[col * 4 + row] = rotation[row * 3 + col]
        m[12] = translation[0]; m[13] = translation[1]; m[14] = translation[2]
        m[15] = 1f
        return m
    }

    @Test
    fun `a bridged pose rotates translation by the same delta as rotation`() {
        val tracker = mockk<HomographyArTracker>()
        val bridge = mockk<GyroOrientationBridge>(relaxed = true)
        val frame = mockk<Bitmap>()

        // Vision locks once, with the camera 2 units back along Z (identity rotation).
        val lockedPose = HomographyPose(viewMatrixOf(identityRotation, floatArrayOf(0f, 0f, -2f)), 0.9f)
        every { tracker.track(any(), any(), any(), any(), any()) } returns lockedPose andThen null
        // Bridge reports a 90-degree pan since that lock, well inside the bridge window.
        every { bridge.msSinceReference() } returns 100L
        every { bridge.cameraRotationDelta(any()) } returns ninetyAboutY

        val bridgedTracker = BridgedHomographyTracker(
            context = mockk<Context>(relaxed = true),
            tracker = tracker,
            bridge = bridge,
        )

        bridgedTracker.trackFrame(frame, 1f, 1f, 0f, 0f, 0) // vision succeeds; marks the reference.
        val bridged = requireNotNull(bridgedTracker.trackFrame(frame, 1f, 1f, 0f, 0f, 0)) // vision fails; bridged.

        // ninetyAboutY * (0,0,-2) = (-2, 0, 0) — the translation must move with the rotation, not
        // stay at (0,0,-2). Column-major 4x4: translation lives at indices 12/13/14.
        assertEquals("translation.x", -2f, bridged.viewMatrix[12], 1e-5f)
        assertEquals("translation.y", 0f, bridged.viewMatrix[13], 1e-5f)
        assertEquals("translation.z", 0f, bridged.viewMatrix[14], 1e-5f)
    }
}
