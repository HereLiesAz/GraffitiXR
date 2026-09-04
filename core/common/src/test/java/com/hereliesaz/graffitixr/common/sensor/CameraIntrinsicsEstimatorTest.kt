package com.hereliesaz.graffitixr.common.sensor

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [CameraIntrinsicsEstimator.computeIntrinsics] — the pure math behind the estimator, with
 * no `CameraCharacteristics`/`Size`/`SizeF` mocking involved. (An earlier version of this test
 * mocked `CameraCharacteristics` directly and consistently threw `ClassCastException` at replay
 * time whenever a stub for this generic `get()` method returned an actual `Size`/`SizeF` instance
 * rather than null — a mockk/Android-stub-jar interaction, not a bug in the code under test. The
 * `CameraCharacteristics`-reading layer, [CameraIntrinsicsEstimator.estimateFrom], is intentionally
 * left untested for the same reason [CameraCapabilitiesTest][com.hereliesaz.graffitixr.common.util.CameraCapabilitiesTest]
 * keeps ITS characteristics-reading shallow — see this class's own doc.)
 */
class CameraIntrinsicsEstimatorTest {

    @Test
    fun `prefers LENS_INTRINSIC_CALIBRATION when present, rescaled to the target size`() {
        // Native 4032x3024 calibration, requested at a 1/4-scale 1008x756 analysis frame.
        val result = CameraIntrinsicsEstimator.computeIntrinsics(
            calibration = floatArrayOf(3200f, 3200f, 2016f, 1512f, 0f),
            pixelArrayWidth = 4032,
            pixelArrayHeight = 3024,
            focalLengthMm = null,
            sensorWidthMm = null,
            sensorHeightMm = null,
            targetWidth = 1008,
            targetHeight = 756,
        )
        assertEquals(CameraIntrinsics(fx = 800f, fy = 800f, cx = 504f, cy = 378f, width = 1008, height = 756), result)
    }

    @Test
    fun `falls back to focal-length and sensor-size when no calibration is present`() {
        val result = CameraIntrinsicsEstimator.computeIntrinsics(
            calibration = null,
            pixelArrayWidth = 4032,
            pixelArrayHeight = 3024,
            focalLengthMm = 4.25f,
            sensorWidthMm = 6.4f,
            sensorHeightMm = 4.8f,
            targetWidth = 4032,
            targetHeight = 3024,
        )
        requireNotNull(result)
        // fx = focalLengthMm * pixelArrayWidthPx / sensorWidthMm = 4.25 * 4032 / 6.4
        assertTrue(abs(result.fx - (4.25f * 4032f / 6.4f)) < 1e-2f)
        assertTrue(abs(result.fy - (4.25f * 3024f / 4.8f)) < 1e-2f)
        // Principal point assumed centered when falling back.
        assertEquals(2016f, result.cx, 1e-2f)
        assertEquals(1512f, result.cy, 1e-2f)
    }

    @Test
    fun `falls back when calibration has fewer than 4 elements`() {
        val result = CameraIntrinsicsEstimator.computeIntrinsics(
            calibration = floatArrayOf(1f, 2f, 3f), // truncated/malformed
            pixelArrayWidth = 4032,
            pixelArrayHeight = 3024,
            focalLengthMm = 4.25f,
            sensorWidthMm = 6.4f,
            sensorHeightMm = 4.8f,
            targetWidth = 4032,
            targetHeight = 3024,
        )
        requireNotNull(result)
        assertTrue(abs(result.fx - (4.25f * 4032f / 6.4f)) < 1e-2f)
    }

    @Test
    fun `rescales the fallback estimate to a smaller analysis frame`() {
        val result = CameraIntrinsicsEstimator.computeIntrinsics(
            calibration = null,
            pixelArrayWidth = 4032,
            pixelArrayHeight = 3024,
            focalLengthMm = 4.25f,
            sensorWidthMm = 6.4f,
            sensorHeightMm = 4.8f,
            targetWidth = 2016,
            targetHeight = 1512,
        )
        requireNotNull(result)
        assertTrue(abs(result.fx - (4.25f * 4032f / 6.4f) * 0.5f) < 1e-2f)
        assertEquals(1008f, result.cx, 1e-2f)
    }

    @Test
    fun `returns null when neither calibration nor a focal length is available`() {
        assertNull(
            CameraIntrinsicsEstimator.computeIntrinsics(
                calibration = null, pixelArrayWidth = 4032, pixelArrayHeight = 3024,
                focalLengthMm = null, sensorWidthMm = 6.4f, sensorHeightMm = 4.8f,
                targetWidth = 1920, targetHeight = 1080,
            ),
        )
    }

    @Test
    fun `returns null when the pixel array size is unavailable`() {
        assertNull(
            CameraIntrinsicsEstimator.computeIntrinsics(
                calibration = null, pixelArrayWidth = 0, pixelArrayHeight = 0,
                focalLengthMm = 4.25f, sensorWidthMm = 6.4f, sensorHeightMm = 4.8f,
                targetWidth = 1920, targetHeight = 1080,
            ),
        )
    }

    @Test
    fun `returns null when sensor physical size is unavailable and there is no calibration to fall back from`() {
        assertNull(
            CameraIntrinsicsEstimator.computeIntrinsics(
                calibration = null, pixelArrayWidth = 4032, pixelArrayHeight = 3024,
                focalLengthMm = 4.25f, sensorWidthMm = null, sensorHeightMm = null,
                targetWidth = 1920, targetHeight = 1080,
            ),
        )
    }

    @Test
    fun `returns null for a non-positive target size`() {
        val calibration = floatArrayOf(3200f, 3200f, 2016f, 1512f, 0f)
        assertNull(
            CameraIntrinsicsEstimator.computeIntrinsics(
                calibration = calibration, pixelArrayWidth = 4032, pixelArrayHeight = 3024,
                focalLengthMm = null, sensorWidthMm = null, sensorHeightMm = null,
                targetWidth = 0, targetHeight = 1080,
            ),
        )
        assertNull(
            CameraIntrinsicsEstimator.computeIntrinsics(
                calibration = calibration, pixelArrayWidth = 4032, pixelArrayHeight = 3024,
                focalLengthMm = null, sensorWidthMm = null, sensorHeightMm = null,
                targetWidth = 1920, targetHeight = -1,
            ),
        )
    }

    @Test
    fun `returns null when the computed focal length is non-positive`() {
        assertNull(
            CameraIntrinsicsEstimator.computeIntrinsics(
                calibration = null, pixelArrayWidth = 4032, pixelArrayHeight = 3024,
                focalLengthMm = 0f, sensorWidthMm = 6.4f, sensorHeightMm = 4.8f,
                targetWidth = 1920, targetHeight = 1080,
            ),
        )
    }
}
