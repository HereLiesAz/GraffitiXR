// FILE: feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/BridgedHomographyTrackerTest.kt
package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.feature.ar.HomographyArTracker.HomographyPose
import com.hereliesaz.sphereslam.attitude.AttitudeSample
import com.hereliesaz.sphereslam.math.RigidMath
import io.mockk.every
import io.mockk.mockk
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * App wiring of the gyro bridge around [HomographyArTracker]: the reference is committed only on a
 * fresh vision lock, a bridged pose holds the camera CENTRE through a pure rotation, the bridge
 * gives up after its window, and the body→camera rotation is SphereSLAM's
 * `DeviceCameraRotation.cameraFromDevice(sensorOrientation, rotationDegrees)` (not
 * `R_z(rotationDegrees)`). The rotation math itself is tested in SphereSLAM.
 */
class BridgedHomographyTrackerTest {

    private val identityQuat = floatArrayOf(0f, 0f, 0f, 1f)

    /** Unit quaternion `[x, y, z, w]` for [deg] about the unit body axis ([ax], [ay], [az]). */
    private fun quat(ax: Float, ay: Float, az: Float, deg: Double): FloatArray {
        val h = Math.toRadians(deg) / 2.0
        val s = sin(h).toFloat()
        return floatArrayOf(ax * s, ay * s, az * s, cos(h).toFloat())
    }

    private fun lockedAtZMinus2() = HomographyPose(
        floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, -2f, 1f,
        ),
        0.9f,
    )

    private class Clock(var ms: Long = 1_000L)

    private fun harness(attitude: () -> AttitudeSample?, clock: Clock): Pair<BridgedHomographyTracker, HomographyArTracker> {
        val tracker = mockk<HomographyArTracker>()
        every { tracker.track(any(), any(), any(), any(), any()) } returns lockedAtZMinus2() andThen null
        return BridgedHomographyTracker(
            tracker = tracker,
            attitudeSource = null,
            latestAttitude = attitude,
            maxBridgeMs = 400L,
            nowMs = { clock.ms },
        ) to tracker
    }

    @Test
    fun `a bridged pose holds the camera centre through a pure rotation`() {
        var q = identityQuat
        val clock = Clock()
        val (bridged, _) = harness({ AttitudeSample(q, 0L) }, clock)
        val frame = mockk<Bitmap>()

        bridged.trackFrame(frame, 1f, 1f, 0f, 0f, rotationDeg = 90, sensorOrientationDeg = 90)
        q = quat(0f, 1f, 0f, 90.0) // a 90° pan about body Y
        clock.ms += 100L
        val out = requireNotNull(
            bridged.trackFrame(frame, 1f, 1f, 0f, 0f, rotationDeg = 90, sensorOrientationDeg = 90),
        )

        // Camera centre of the locked pose is (0, 0, 2); a pure rotation must not move it.
        assertArrayEquals(floatArrayOf(0f, 0f, 2f), RigidMath.cameraCentre(out.viewMatrix), 1e-5f)
        // ...while the translation column does move: t' = ΔR·t with ΔR = R_y(-90°), so (2, 0, 0).
        assertEquals(2f, out.viewMatrix[12], 1e-5f)
        assertEquals(0f, out.viewMatrix[14], 1e-5f)
        // Confidence decays linearly across the 400 ms window.
        assertEquals(0.9f * 0.75f, out.confidence, 1e-5f)
    }

    @Test
    fun `body to camera uses sensor orientation minus applied image rotation`() {
        // Raw buffer (rotationDeg 0) of a 90° sensor: cameraFromDevice = R_z(90°), so a pitch about
        // body X shows up as a rotation about camera Y. The old R_z(rotationDegrees) = identity would
        // have left it about X.
        var q = identityQuat
        val clock = Clock()
        val (bridged, _) = harness({ AttitudeSample(q, 0L) }, clock)
        val frame = mockk<Bitmap>()

        bridged.trackFrame(frame, 1f, 1f, 0f, 0f, rotationDeg = 0, sensorOrientationDeg = 90)
        q = quat(1f, 0f, 0f, 90.0)
        clock.ms += 50L
        val out = requireNotNull(
            bridged.trackFrame(frame, 1f, 1f, 0f, 0f, rotationDeg = 0, sensorOrientationDeg = 90),
        )

        // A rotation about camera Y keeps the camera Y axis fixed: column 1 of R stays (0, 1, 0).
        assertEquals(0f, out.viewMatrix[4], 1e-5f)
        assertEquals(1f, out.viewMatrix[5], 1e-5f)
        assertEquals(0f, out.viewMatrix[6], 1e-5f)
    }

    @Test
    fun `the bridge gives up after its window`() {
        val clock = Clock()
        val (bridged, _) = harness({ AttitudeSample(identityQuat, 0L) }, clock)
        val frame = mockk<Bitmap>()

        bridged.trackFrame(frame, 1f, 1f, 0f, 0f, rotationDeg = 90, sensorOrientationDeg = 90)
        clock.ms += 401L
        assertNull(bridged.trackFrame(frame, 1f, 1f, 0f, 0f, rotationDeg = 90, sensorOrientationDeg = 90))
    }

    @Test
    fun `no attitude sample means no bridge`() {
        val clock = Clock()
        val (bridged, _) = harness({ null }, clock)
        val frame = mockk<Bitmap>()

        bridged.trackFrame(frame, 1f, 1f, 0f, 0f, rotationDeg = 90, sensorOrientationDeg = 90)
        clock.ms += 10L
        assertNull(bridged.trackFrame(frame, 1f, 1f, 0f, 0f, rotationDeg = 90, sensorOrientationDeg = 90))
    }
}
