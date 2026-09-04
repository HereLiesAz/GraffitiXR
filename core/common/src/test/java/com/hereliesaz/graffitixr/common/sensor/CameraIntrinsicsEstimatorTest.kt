package com.hereliesaz.graffitixr.common.sensor

import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import android.util.SizeF
import io.mockk.every
import io.mockk.mockk
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraIntrinsicsEstimatorTest {

    private fun characteristics(
        calibration: FloatArray? = null,
        pixelArray: Size? = Size(4032, 3024),
        focalLengths: FloatArray? = floatArrayOf(4.25f),
        sensorSizeMm: SizeF? = SizeF(6.4f, 4.8f),
    ): CameraCharacteristics {
        val c = mockk<CameraCharacteristics>()
        every { c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) } returns calibration
        every { c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) } returns pixelArray
        every { c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) } returns focalLengths
        every { c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) } returns sensorSizeMm
        return c
    }

    @Test
    fun `prefers LENS_INTRINSIC_CALIBRATION when the device reports it, rescaled to the target size`() {
        // Native 4032x3024 calibration, requested at a 1/4-scale 1008x756 analysis frame.
        val c = characteristics(calibration = floatArrayOf(3200f, 3200f, 2016f, 1512f, 0f))
        val result = CameraIntrinsicsEstimator.estimateFrom(c, targetWidth = 1008, targetHeight = 756)

        assertEquals(CameraIntrinsics(fx = 800f, fy = 800f, cx = 504f, cy = 378f, width = 1008, height = 756), result)
    }

    @Test
    fun `falls back to focal-length and sensor-size when no LENS_INTRINSIC_CALIBRATION exists`() {
        val c = characteristics(calibration = null)
        val result = CameraIntrinsicsEstimator.estimateFrom(c, targetWidth = 4032, targetHeight = 3024)

        requireNotNull(result)
        // fx = focalLengthMm * pixelArrayWidthPx / sensorWidthMm = 4.25 * 4032 / 6.4
        assertTrue(abs(result.fx - (4.25f * 4032f / 6.4f)) < 1e-2f)
        assertTrue(abs(result.fy - (4.25f * 3024f / 4.8f)) < 1e-2f)
        // Principal point assumed centered when falling back.
        assertEquals(2016f, result.cx, 1e-2f)
        assertEquals(1512f, result.cy, 1e-2f)
    }

    @Test
    fun `rescales the fallback estimate to a smaller analysis frame`() {
        val c = characteristics(calibration = null)
        val result = CameraIntrinsicsEstimator.estimateFrom(c, targetWidth = 2016, targetHeight = 1512)

        requireNotNull(result)
        assertTrue(abs(result.fx - (4.25f * 4032f / 6.4f) * 0.5f) < 1e-2f)
        assertEquals(1008f, result.cx, 1e-2f)
    }

    @Test
    fun `returns null when neither calibration nor focal-length fallback data is available`() {
        val c = characteristics(calibration = null, focalLengths = null)
        assertNull(CameraIntrinsicsEstimator.estimateFrom(c, targetWidth = 1920, targetHeight = 1080))
    }

    @Test
    fun `returns null when the pixel array size is missing`() {
        val c = characteristics(pixelArray = null)
        assertNull(CameraIntrinsicsEstimator.estimateFrom(c, targetWidth = 1920, targetHeight = 1080))
    }

    @Test
    fun `returns null when sensor physical size is missing and there is no calibration to fall back from`() {
        val c = characteristics(calibration = null, sensorSizeMm = null)
        assertNull(CameraIntrinsicsEstimator.estimateFrom(c, targetWidth = 1920, targetHeight = 1080))
    }

    @Test
    fun `returns null for a non-positive target size`() {
        val c = characteristics()
        assertNull(CameraIntrinsicsEstimator.estimateFrom(c, targetWidth = 0, targetHeight = 1080))
        assertNull(CameraIntrinsicsEstimator.estimateFrom(c, targetWidth = 1920, targetHeight = -1))
    }
}
