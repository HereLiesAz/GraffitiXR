package com.hereliesaz.graffitixr.feature.ar

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.view.CameraController
import com.hereliesaz.graffitixr.common.model.CameraTargetFps
import timber.log.Timber

/*
 * Camera2 capture-request tuning for the shared CameraX controller.
 *
 * Camera2CameraControl.setCaptureRequestOptions REPLACES every interop option previously set, so the
 * whole option set for each mode is built in one place here: the general (non-AR) default and the
 * standalone SphereSLAM tracking profile. The standalone overlay applies the tracking profile while
 * it is composed and restores the default when it leaves.
 *
 * Standalone tracking profile, and why:
 *  - CONTROL_AE_TARGET_FPS_RANGE pinned (default [30,30]). CameraX leaves ImageAnalysis with no
 *    target frame rate, so the HAL's default AE range (typically [15,30] or wider) lets AE stretch
 *    exposure in dim light and halve the frame rate to ~15 fps — which halves tracking updates, and
 *    the longer exposure adds motion blur that costs KPM matches. A fixed range trades a slightly
 *    darker/noisier frame (AE raises gain instead of exposure time) for a steady rate; KPM is far
 *    more tolerant of noise than of blur. Follows Settings → Camera target FPS (and its adaptive
 *    battery cap) so 60 / Device max apply to standalone too; Device default pins 30, because the
 *    device's own variable range is exactly the problem being fixed.
 *  - Video stabilization (EIS) OFF. EIS warps/crops the preview stream from a buffered look-ahead,
 *    adding preview latency, and on most HALs it applies to the preview but not the YUV analysis
 *    stream — so the artwork, posed from the un-stabilized analysis frame, would be drawn over a
 *    preview that is shifted and delayed relative to it. The intrinsics the tracker uses also
 *    assume the unwarped sensor geometry.
 *  - Optical stabilization (OIS) kept ON. It moves the lens for both streams alike (no preview vs.
 *    analysis desync, no added latency) and reduces blur, which helps KPM.
 */

/** Ranges are `[lower, upper]` pairs (Camera2 `Range<Int>` unpacked, so the policy is JVM-testable). */
internal object StandaloneCameraTuning {

    /** The standalone target when the setting does not name a rate ([CameraTargetFps.DEVICE_DEFAULT]). */
    const val DEFAULT_STANDALONE_FPS = CameraTargetFps.FPS_30

    /**
     * The AE target FPS range for standalone tracking, chosen from the camera's
     * `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`, or null when none are reported (leave AE alone).
     *
     * @param effectiveTargetFps the Settings value after the adaptive-rate cap
     *   ([effectiveCameraTargetFps]): a real rate, [CameraTargetFps.DEVICE_DEFAULT] (→ 30) or
     *   [CameraTargetFps.DEVICE_MAX].
     */
    fun chooseAeTargetFpsRange(available: List<IntArray>, effectiveTargetFps: Int): IntArray? {
        val valid = available.filter { it.size == 2 && it[0] > 0 && it[1] >= it[0] }
        if (valid.isEmpty()) return null
        if (effectiveTargetFps == CameraTargetFps.DEVICE_MAX) {
            // Highest upper bound; among those the highest floor (a fixed range beats a variable one).
            return valid.maxWithOrNull(compareBy<IntArray>({ it[1] }, { it[0] }))
        }
        val want = if (effectiveTargetFps > 0) effectiveTargetFps else DEFAULT_STANDALONE_FPS
        // Closest upper bound to the target; on a tie in distance the lower upper bound (less power,
        // longer max exposure); then the highest floor, so [30,30] beats [15,30].
        return valid.minWithOrNull(
            compareBy<IntArray>({ kotlin.math.abs(it[1] - want) }, { it[1] }, { -it[0] }),
        )
    }
}

/** General (non-standalone) capture options — the shared controller's historical default. */
@ExperimentalCamera2Interop
internal fun defaultCaptureRequestOptions(): CaptureRequestOptions =
    CaptureRequestOptions.Builder()
        .setCaptureRequestOption(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON,
        )
        .setCaptureRequestOption(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON,
        )
        .build()

/** Standalone SphereSLAM tracking options (see the file comment for the reasoning). */
@ExperimentalCamera2Interop
internal fun standaloneTrackingCaptureRequestOptions(aeFpsRange: Range<Int>?): CaptureRequestOptions =
    CaptureRequestOptions.Builder()
        .setCaptureRequestOption(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
        )
        .setCaptureRequestOption(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON,
        )
        .apply {
            if (aeFpsRange != null) {
                setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, aeFpsRange)
            }
        }
        .build()

/** Apply the general default options to [controller]'s bound camera (no-op when unbound). */
@ExperimentalCamera2Interop
internal fun applyDefaultCaptureOptions(controller: CameraController) {
    val control = controller.cameraControl ?: return
    runCatching {
        Camera2CameraControl.from(control).setCaptureRequestOptions(defaultCaptureRequestOptions())
    }.onFailure { Timber.w(it, "Default camera capture options not applied") }
}

/**
 * Apply the standalone tracking profile for [effectiveTargetFps] to [controller]'s bound camera.
 * Returns the AE range applied (`[lower, upper]`), or null when unbound / unreported.
 */
@ExperimentalCamera2Interop
internal fun applyStandaloneTrackingCaptureOptions(
    controller: CameraController,
    effectiveTargetFps: Int,
): IntArray? {
    val control = controller.cameraControl ?: return null
    val info = controller.cameraInfo
    val available = info?.let {
        runCatching {
            Camera2CameraInfo.from(it)
                .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        }.getOrNull()
    }?.map { intArrayOf(it.lower, it.upper) }.orEmpty()
    val chosen = StandaloneCameraTuning.chooseAeTargetFpsRange(available, effectiveTargetFps)
    val range = chosen?.let { Range(it[0], it[1]) }
    runCatching {
        Camera2CameraControl.from(control)
            .setCaptureRequestOptions(standaloneTrackingCaptureRequestOptions(range))
    }.onFailure {
        Timber.w(it, "Standalone camera capture options not applied")
        return null
    }
    return chosen
}
