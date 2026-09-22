// FILE: core/common/src/main/java/com/hereliesaz/graffitixr/common/sensor/CameraIntrinsicsEstimator.kt
package com.hereliesaz.graffitixr.common.sensor

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import timber.log.Timber

/**
 * Estimates [CameraIntrinsics] from Camera2's `CameraCharacteristics` — the piece ARCore's
 * `Camera.getImageIntrinsics()` supplies for free and CameraX doesn't, needed by
 * [com.hereliesaz.graffitixr.feature.ar.rendering.ProjectionMatrix] for the ARCore-unavailable
 * fallback (`com.hereliesaz.graffitixr.feature.ar.BridgedHomographyTracker`).
 *
 * **Precision, in descending order of what's actually available:**
 * 1. `LENS_INTRINSIC_CALIBRATION` — a real, per-device-calibrated `[fx, fy, cx, cy, skew]` (skew
 *    dropped; this app's projection has never needed it). Optional per the Camera2 API — many
 *    devices don't report it.
 * 2. Otherwise, the classic pinhole approximation from the lens' nominal focal length and the
 *    sensor's physical size: `fx = focalLengthMm · pixelArrayWidthPx / sensorWidthMm` (and the `y`
 *    equivalent), principal point assumed exactly centered. This is a real approximation — no lens
 *    is perfectly centered or distortion-free — consistent with this whole fallback's character
 *    (see `HomographyTracker.h`'s class doc): good enough to size and orient an overlay, not a
 *    photogrammetry-grade calibration.
 *
 * The two paths are defined against different denominators. `LENS_INTRINSIC_CALIBRATION`'s
 * `[cx, cy]` (and the array its `[fx, fy]` are scaled from) is specified relative to
 * `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE` — the sensor's active array *before* lens-shading
 * / distortion correction crops it down — not `SENSOR_INFO_PIXEL_ARRAY_SIZE` (the FULL pixel
 * array). The pinhole-approximation fallback has no such distinction and is genuinely defined
 * against the full pixel array. Either way, that denominator is very unlikely to be the resolution
 * actual captured frames arrive at (cropping, binning, a non-native aspect ratio) — [estimate]
 * rescales the result to the caller's actual frame size, assuming a uniform, uncropped scale
 * between the two (true for the common case of the full sensor FOV read out at a lower resolution;
 * an aspect-ratio-changing crop would need the crop rect too, which this does not attempt to
 * source).
 *
 * Split deliberately into a thin `CameraCharacteristics`-reading layer ([estimate]/[estimateFrom])
 * and a pure computation ([computeIntrinsics]) taking plain numbers. `CameraCharacteristics.get`'s
 * generic signature (returning `Size`/`SizeF`/`float[]` depending on the key) does not mock
 * reliably here across multiple stubbed keys on one instance — mirrors [CameraCapabilities]' own
 * choice not to unit-test characteristics-reading depth. The actual math is what matters and is
 * what [CameraIntrinsicsEstimatorTest] pins, unconditionally on the JVM, no mocking involved.
 */
object CameraIntrinsicsEstimator {

    /**
     * @param cameraId a Camera2 camera id — from `androidx.camera.camera2.interop.Camera2CameraInfo`
     *   for a CameraX camera, since this object takes no CameraX dependency of its own.
     * @param targetWidth,targetHeight the resolution actual frames will arrive at (e.g. an
     *   `ImageAnalysis` frame's size), which the sensor's raw calibration is rescaled to match.
     * @return null if the camera service or this camera id can't be reached, or the resulting
     *   values would be non-finite/non-positive — never a plausible-looking guess (the standing
     *   rule this codebase already follows for absent sensor data, e.g. [DeviceAttitudeProvider]).
     */
    fun estimate(context: Context, cameraId: String, targetWidth: Int, targetHeight: Int): CameraIntrinsics? {
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
            val characteristics = manager.getCameraCharacteristics(cameraId)
            estimateFrom(characteristics, targetWidth, targetHeight)
        } catch (e: Exception) {
            Timber.e(e, "CameraIntrinsicsEstimator: failed to read characteristics for camera $cameraId")
            null
        }
    }

    /** Reads the raw Camera2 fields and delegates the actual math to [computeIntrinsics]. */
    internal fun estimateFrom(
        characteristics: CameraCharacteristics,
        targetWidth: Int,
        targetHeight: Int,
    ): CameraIntrinsics? {
        val calibration = characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        val pixelArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        // LENS_INTRINSIC_CALIBRATION is defined against this rect, not the full pixel array — see
        // the class doc. Falls back to the pixel array on devices/API levels where it's absent.
        val preCorrectionArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val sensorSizeMm = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        return computeIntrinsics(
            calibration = calibration,
            pixelArrayWidth = pixelArray?.width ?: 0,
            pixelArrayHeight = pixelArray?.height ?: 0,
            focalLengthMm = focalLengths?.firstOrNull(),
            sensorWidthMm = sensorSizeMm?.width,
            sensorHeightMm = sensorSizeMm?.height,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            calibrationArrayWidth = preCorrectionArray?.width() ?: (pixelArray?.width ?: 0),
            calibrationArrayHeight = preCorrectionArray?.height() ?: (pixelArray?.height ?: 0),
        )
    }

    /**
     * The actual pinhole math — see the class doc's precision section — as a pure function over
     * plain numbers, with no Android/Camera2 type in sight. `pixelArrayWidth`/`Height` <= 0 means
     * "not available" (a Kotlin nullable `Size` doesn't survive this seam as cleanly as the other
     * nullable numbers, so absence is `0`, which is never a real sensor dimension).
     *
     * @param calibrationArrayWidth,calibrationArrayHeight the array `calibration` (when present) is
     *   defined against — `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE`, NOT the full pixel array
     *   (see the class doc). Defaults to [pixelArrayWidth]/[pixelArrayHeight] for callers (and the
     *   pinhole-fallback branch, which genuinely is defined against the full pixel array) that don't
     *   have a distinct pre-correction size.
     */
    internal fun computeIntrinsics(
        calibration: FloatArray?,
        pixelArrayWidth: Int,
        pixelArrayHeight: Int,
        focalLengthMm: Float?,
        sensorWidthMm: Float?,
        sensorHeightMm: Float?,
        targetWidth: Int,
        targetHeight: Int,
        calibrationArrayWidth: Int = pixelArrayWidth,
        calibrationArrayHeight: Int = pixelArrayHeight,
    ): CameraIntrinsics? {
        if (targetWidth <= 0 || targetHeight <= 0) return null
        if (pixelArrayWidth <= 0 || pixelArrayHeight <= 0) return null

        val raw: FloatArray
        val rawArrayWidth: Int
        val rawArrayHeight: Int
        if (calibration != null && calibration.size >= 4) {
            raw = floatArrayOf(calibration[0], calibration[1], calibration[2], calibration[3])
            // calibration[2]/[3] (cx, cy) are given against the pre-correction active array, not the
            // full pixel array — fall back to the pixel array only when that size is unavailable.
            rawArrayWidth = if (calibrationArrayWidth > 0) calibrationArrayWidth else pixelArrayWidth
            rawArrayHeight = if (calibrationArrayHeight > 0) calibrationArrayHeight else pixelArrayHeight
        } else {
            if (focalLengthMm == null) return null
            if (sensorWidthMm == null || sensorHeightMm == null) return null
            if (sensorWidthMm <= 0f || sensorHeightMm <= 0f) return null
            raw = floatArrayOf(
                focalLengthMm * pixelArrayWidth / sensorWidthMm,
                focalLengthMm * pixelArrayHeight / sensorHeightMm,
                pixelArrayWidth / 2f,
                pixelArrayHeight / 2f,
            )
            rawArrayWidth = pixelArrayWidth
            rawArrayHeight = pixelArrayHeight
        }
        if (raw.any { !it.isFinite() } || raw[0] <= 0f || raw[1] <= 0f) return null

        val scaleX = targetWidth.toFloat() / rawArrayWidth
        val scaleY = targetHeight.toFloat() / rawArrayHeight
        return CameraIntrinsics(
            fx = raw[0] * scaleX,
            fy = raw[1] * scaleY,
            cx = raw[2] * scaleX,
            cy = raw[3] * scaleY,
            width = targetWidth,
            height = targetHeight,
        )
    }
}
