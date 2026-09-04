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
 * Either way the characteristics describe the sensor's FULL pixel array, which is very unlikely to
 * be the resolution actual captured frames arrive at (cropping, binning, a non-native aspect
 * ratio) — [estimate] rescales the result to the caller's actual frame size, assuming a uniform,
 * uncropped scale between the two (true for the common case of the full sensor FOV read out at a
 * lower resolution; an aspect-ratio-changing crop would need the crop rect too, which this does
 * not attempt to source).
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
        if (targetWidth <= 0 || targetHeight <= 0) return null
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
            val characteristics = manager.getCameraCharacteristics(cameraId)
            estimateFrom(characteristics, targetWidth, targetHeight)
        } catch (e: Exception) {
            Timber.e(e, "CameraIntrinsicsEstimator: failed to read characteristics for camera $cameraId")
            null
        }
    }

    /** The logic above, split out so a test can supply [CameraCharacteristics] directly. */
    internal fun estimateFrom(
        characteristics: CameraCharacteristics,
        targetWidth: Int,
        targetHeight: Int,
    ): CameraIntrinsics? {
        if (targetWidth <= 0 || targetHeight <= 0) return null
        val calibration = characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        val pixelArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: return null
        val sourceW = pixelArray.width
        val sourceH = pixelArray.height
        if (sourceW <= 0 || sourceH <= 0) return null

        val raw = if (calibration != null && calibration.size >= 4) {
            floatArrayOf(calibration[0], calibration[1], calibration[2], calibration[3])
        } else {
            val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val focalLengthMm = focalLengths?.firstOrNull() ?: return null
            val sensorSizeMm = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
            if (sensorSizeMm.width <= 0f || sensorSizeMm.height <= 0f) return null
            floatArrayOf(
                focalLengthMm * sourceW / sensorSizeMm.width,
                focalLengthMm * sourceH / sensorSizeMm.height,
                sourceW / 2f,
                sourceH / 2f,
            )
        }
        if (raw.any { !it.isFinite() } || raw[0] <= 0f || raw[1] <= 0f) return null

        val scaleX = targetWidth.toFloat() / sourceW
        val scaleY = targetHeight.toFloat() / sourceH
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
