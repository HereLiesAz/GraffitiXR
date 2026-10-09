package com.hereliesaz.graffitixr.common.sensor

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import android.util.SizeF
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCameraCharacteristics

/**
 * BACKLOG Phase 7: the CameraX tracking paths (homography Overlay fallback, standalone SphereSLAM,
 * co-op peer analyzer) hand a Camera2 id to [CameraIntrinsicsEstimator.estimate], which reads the
 * real CameraManager — "not mocked" on a plain JVM, so this id→characteristics seam had no test.
 * Robolectric supplies a CameraManager with registered cameras. Expected values are hand-computed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraIntrinsicsEstimatorCameraIdTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Camera "0": no calibration → pinhole from 4.0 mm lens on a 6.4 x 4.8 mm, 4000 x 3000 sensor.
        val pinhole = ShadowCameraCharacteristics.newCameraCharacteristics()
        shadowOf(pinhole).set(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE, Size(4000, 3000))
        shadowOf(pinhole).set(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS, floatArrayOf(4.0f))
        shadowOf(pinhole).set(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE, SizeF(6.4f, 4.8f))
        shadowOf(manager).addCamera("0", pinhole)

        // Camera "1": per-device calibration against a 4000 x 3000 pre-correction array.
        val calibrated = ShadowCameraCharacteristics.newCameraCharacteristics()
        shadowOf(calibrated).set(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE, Size(4032, 3024))
        shadowOf(calibrated).set(
            CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE, Rect(0, 0, 4000, 3000),
        )
        shadowOf(calibrated).set(
            CameraCharacteristics.LENS_INTRINSIC_CALIBRATION, floatArrayOf(3000f, 3000f, 1990f, 1510f, 0f),
        )
        shadowOf(manager).addCamera("1", calibrated)
    }

    @Test
    fun `pinhole camera id yields focal-length intrinsics at the frame size`() {
        val k = CameraIntrinsicsEstimator.estimate(context, "0", 640, 480)
        assertNotNull(k)
        // fx = 4.0 * 4000 / 6.4 = 2500 px at full res; x 640/4000 = 0.16 -> 400.
        assertEquals(400f, k!!.fx, 1e-3f)
        assertEquals(400f, k.fy, 1e-3f)
        assertEquals(320f, k.cx, 1e-3f)
        assertEquals(240f, k.cy, 1e-3f)
    }

    @Test
    fun `calibrated camera id uses its calibration against the pre-correction array`() {
        val k = CameraIntrinsicsEstimator.estimate(context, "1", 640, 480)!!
        // 3000 * 0.16 = 480; 1990 * 0.16 = 318.4; 1510 * 0.16 = 241.6.
        assertEquals(480f, k.fx, 1e-3f)
        assertEquals(318.4f, k.cx, 1e-3f)
        assertEquals(241.6f, k.cy, 1e-3f)
    }

    @Test
    fun `an id the camera service does not know yields null, not a guess or a crash`() {
        assertNull(CameraIntrinsicsEstimator.estimate(context, "7", 640, 480))
    }

    @Test
    fun `the id selects the camera - same frame size, different answers`() {
        val a = CameraIntrinsicsEstimator.estimate(context, "0", 640, 480)!!
        val b = CameraIntrinsicsEstimator.estimate(context, "1", 640, 480)!!
        assertEquals(400f, a.fx, 1e-3f)
        assertEquals(480f, b.fx, 1e-3f)
    }
}
