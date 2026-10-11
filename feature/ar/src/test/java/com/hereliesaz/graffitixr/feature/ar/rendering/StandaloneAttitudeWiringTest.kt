package com.hereliesaz.graffitixr.feature.ar.rendering

import com.hereliesaz.graffitixr.feature.ar.StandaloneFrameAttitude
import com.hereliesaz.sphereslam.attitude.AttitudeExtrapolator
import com.hereliesaz.sphereslam.attitude.AttitudeSample
import com.hereliesaz.sphereslam.attitude.DeviceCameraRotation
import com.hereliesaz.sphereslam.attitude.DisplayPosePredictor
import com.hereliesaz.sphereslam.camera.CameraFillCenter
import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The app's attitude wiring session → predictor → renderer, without GL: the tracking session
 * publishes a pose with its frame's capture-time attitude and `cameraFromDevice`
 * ([DisplayPosePredictor.update], as `PhotosphereTrackingSession.publishToPredictor` does); the
 * renderer's [StandaloneAttitudeWiring] re-poses it per draw and rotates the glow with the display
 * frame; [GlowLensCache] decides when the glow lens is rebuilt.
 */
class StandaloneAttitudeWiringTest {

    private val identityQuat = floatArrayOf(0f, 0f, 0f, 1f)

    // A rear camera with SENSOR_ORIENTATION 90 and an unrotated buffer: a quarter turn, not identity.
    private val cameraFromDevice = DeviceCameraRotation.cameraFromDevice(90, 0)

    private val view = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0.3f, -0.2f, -2f, 1f,
    )

    private fun aboutX(deg: Float): FloatArray {
        val half = Math.toRadians(deg / 2.0)
        return floatArrayOf(sin(half).toFloat(), 0f, 0f, cos(half).toFloat())
    }

    private fun quatToMat(q: FloatArray): FloatArray {
        val (x, y, z, w) = q
        return floatArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
            2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
            2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y),
        )
    }

    private fun mul(a: FloatArray, b: FloatArray) = FloatArray(9) { k ->
        val i = k / 3
        val j = k % 3
        a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
    }

    private fun transpose(a: FloatArray) = FloatArray(9) { k -> a[(k % 3) * 3 + k / 3] }

    private fun mulVec(a: FloatArray, v: FloatArray) = FloatArray(3) { i ->
        a[i * 3] * v[0] + a[i * 3 + 1] * v[1] + a[i * 3 + 2] * v[2]
    }

    /** Row-major rotation block of a column-major view. */
    private fun rotationOf(view: FloatArray) = floatArrayOf(
        view[0], view[4], view[8],
        view[1], view[5], view[9],
        view[2], view[6], view[10],
    )

    private fun history(vararg samples: Pair<Long, FloatArray>) =
        AttitudeExtrapolator().apply { samples.forEach { (t, q) -> push(q, t) } }

    @Test
    fun `predicted artwork rotates about the device axis seen through a non-identity cameraFromDevice`() {
        val attitude = history(0L to identityQuat, 20_000_000L to aboutX(12f))
        val predictor = DisplayPosePredictor()
        // What the session publishes for a pose solved from the frame at t = 0.
        predictor.update(view, identityQuat, cameraFromDevice, 0L)
        val wiring = StandaloneAttitudeWiring().apply { set(attitude, predictor) }

        val drawn = wiring.viewForDraw(20_000_000L, view, FloatArray(16))
        val r = rotationOf(drawn)
        val r0 = rotationOf(view)
        // Camera-frame delta actually applied.
        val delta = mul(r, transpose(r0))
        // Expected: C · R(q_now)ᵀ · R(q_frame) · Cᵀ.
        val expected = mul(mul(cameraFromDevice, mul(transpose(quatToMat(aboutX(12f))), quatToMat(identityQuat))), transpose(cameraFromDevice))
        for (i in 0 until 9) assertEquals("delta[$i]", expected[i], delta[i], 1e-4f)
        // The rotation axis is the device x axis expressed in the CAMERA frame (C · eₓ), which a
        // quarter-turn C moves off the camera x axis: an identity C would rotate about the wrong axis.
        val axisInCamera = mulVec(cameraFromDevice, floatArrayOf(1f, 0f, 0f))
        val rotatedAxis = mulVec(delta, axisInCamera)
        for (i in 0..2) assertEquals(axisInCamera[i], rotatedAxis[i], 1e-4f)
        assertTrue(kotlin.math.abs(axisInCamera[0]) < 1e-4f)
        // Camera centre (−Rᵀt) held fixed.
        fun centre(v: FloatArray) = FloatArray(3) { i -> -(v[i * 4] * v[12] + v[i * 4 + 1] * v[13] + v[i * 4 + 2] * v[14]) }
        val c0 = centre(view)
        val c1 = centre(drawn)
        for (i in 0..2) assertEquals(c0[i], c1[i], 1e-4f)
    }

    @Test
    fun `the predictor is anchored at the frame's capture-time attitude, not the latest sample`() {
        // Frame captured at 10 ms; analyze() runs at 40 ms, after the device kept rotating.
        val attitude = history(
            0L to identityQuat,
            10_000_000L to aboutX(5f),
            40_000_000L to aboutX(20f),
        )
        val latest = AttitudeSample(aboutX(20f), 40_000_000L)
        val frameAttitude = StandaloneFrameAttitude.atCapture(
            latest = latest,
            history = attitude,
            frameTimestampNs = 10_000_000L,
            frameTimestampIsRealtime = true,
            scratch = FloatArray(4),
        )
        assertNotNull(frameAttitude)
        assertEquals(10_000_000L, frameAttitude!!.timestampNs)
        val predictor = DisplayPosePredictor().apply {
            update(view, frameAttitude, cameraFromDevice, 10_000_000L)
        }
        val wiring = StandaloneAttitudeWiring().apply { set(attitude, predictor) }
        // Drawn at the frame's own time: exactly the vision pose (no spurious rotation).
        val atFrame = wiring.viewForDraw(10_000_000L, view, FloatArray(16))
        for (i in 0 until 16) assertEquals(view[i], atFrame[i], 1e-4f)

        // Anchored at the latest sample instead, the same draw would be rotated by −15°.
        val wrong = DisplayPosePredictor().apply { update(view, latest, cameraFromDevice, 10_000_000L) }
        val wrongDraw = StandaloneAttitudeWiring().apply { set(attitude, wrong) }
            .viewForDraw(10_000_000L, view, FloatArray(16))
        assertTrue((0 until 16).any { kotlin.math.abs(wrongDraw[it] - view[it]) > 1e-2f })
    }

    @Test
    fun `without an attitude source there is no prediction and no glow rotation`() {
        val wiring = StandaloneAttitudeWiring()
        val last = view.copyOf()
        assertSame(last, wiring.viewForDraw(0L, last, FloatArray(16)))
        assertFalse(wiring.worldFromCameraAt(0L, FloatArray(9)))
        wiring.set(null, DisplayPosePredictor().apply { update(view, identityQuat, cameraFromDevice) })
        assertSame(last, wiring.viewForDraw(0L, last, FloatArray(16)))
    }

    @Test
    fun `clearPrediction drops the held pose`() {
        val predictor = DisplayPosePredictor().apply { update(view, identityQuat, cameraFromDevice) }
        val wiring = StandaloneAttitudeWiring().apply { set(history(0L to identityQuat), predictor) }
        wiring.clearPrediction()
        val last = view.copyOf()
        assertSame(last, wiring.viewForDraw(0L, last, FloatArray(16)))
    }

    @Test
    fun `glow rotation follows the display rotation`() {
        val q = aboutX(30f)
        val wiring = StandaloneAttitudeWiring().apply { set(history(0L to q), null) }
        val out = FloatArray(9)
        for (rotation in listOf(0, 90, 180, 270)) {
            wiring.setDisplayRotation(rotation)
            assertTrue(wiring.worldFromCameraAt(0L, out))
            val expected = mul(quatToMat(q), transpose(DeviceCameraRotation.cameraFromDeviceForDisplayFrame(rotation)))
            for (i in 0 until 9) assertEquals("rot=$rotation [$i]", expected[i], out[i], 1e-4f)
        }
        // A quarter turn really changes the glow's camera frame (it is not ignored).
        wiring.setDisplayRotation(0)
        val upright = FloatArray(9).also { wiring.worldFromCameraAt(0L, it) }
        wiring.setDisplayRotation(90)
        val turned = FloatArray(9).also { wiring.worldFromCameraAt(0L, it) }
        assertTrue((0 until 9).any { kotlin.math.abs(upright[it] - turned[it]) > 0.5f })
    }

    @Test
    fun `glow lens is rebuilt only when the frame intrinsics or the surface size change`() {
        val cache = GlowLensCache()
        val frame = CameraIntrinsics(fx = 500f, fy = 500f, cx = 240f, cy = 320f, width = 480, height = 640)
        assertNull(cache.lensIntrinsicsIfChanged(null, 1080, 2400))

        val first = cache.lensIntrinsicsIfChanged(frame, 1080, 2400)
        assertEquals(CameraFillCenter.intrinsics(frame, 1080, 2400), first)
        // Same key (an equal-by-value copy too): nothing to rebuild.
        assertNull(cache.lensIntrinsicsIfChanged(frame, 1080, 2400))
        assertNull(cache.lensIntrinsicsIfChanged(frame.copy(), 1080, 2400))
        // Surface rotated / resized.
        assertEquals(CameraFillCenter.intrinsics(frame, 2400, 1080), cache.lensIntrinsicsIfChanged(frame, 2400, 1080))
        // New calibration.
        val recalibrated = frame.copy(fx = 520f, fy = 520f)
        assertEquals(
            CameraFillCenter.intrinsics(recalibrated, 2400, 1080),
            cache.lensIntrinsicsIfChanged(recalibrated, 2400, 1080),
        )
        assertNull(cache.lensIntrinsicsIfChanged(recalibrated, 2400, 1080))
    }
}
