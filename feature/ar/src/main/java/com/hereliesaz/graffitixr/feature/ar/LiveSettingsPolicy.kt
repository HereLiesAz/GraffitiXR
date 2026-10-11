package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.graffitixr.common.model.CameraTargetFps

/*
 * Pure decisions behind the AR settings that are applied to the running app the moment they are
 * toggled (Settings → Use ARCore / ARCore depth / Monocular depth / Camera target FPS / Adaptive
 * rate). ArViewModel re-evaluates these on every change; keeping them here keeps the rules testable
 * without an ARCore Session or a device.
 */

/**
 * Whether the ARCore backend is selected: ARCore must be fully installed and supported on this
 * device ([checkResult], resolved once per process — a later install is not auto-detected) AND the
 * artist must not have turned "Use ARCore" off. Anything else takes the CameraX + SphereSLAM
 * standalone path. Re-derived on every toggle of [arCoreOptIn].
 */
internal fun isArCoreBackendSelected(
    checkResult: ArAvailabilityChecker.Result,
    arCoreOptIn: Boolean,
): Boolean = checkResult == ArAvailabilityChecker.Result.Supported && arCoreOptIn

/**
 * Whether the monocular depth model should be loaded: the setting is on and ARCore's own Depth API
 * will not be the depth source (no ARCore, ARCore off, or ARCore depth off). This setting-level
 * check can't see the device's isDepthModeSupported, which needs a live session.
 */
internal fun isMonocularDepthWanted(
    monocularDepthOptIn: Boolean,
    arCoreOptIn: Boolean,
    arCoreDepthOptIn: Boolean,
    arCoreSupported: Boolean,
): Boolean = monocularDepthOptIn && !(arCoreOptIn && arCoreDepthOptIn && arCoreSupported)

/**
 * The camera frame rate the ARCore session should run at: the artist's [targetFps], except that
 * under battery pressure (tier ≥ medium) with adaptive rate on it is capped to 30 fps — including
 * DEVICE_MAX, the option most likely to cook the phone.
 */
internal fun effectiveCameraTargetFps(
    targetFps: Int,
    adaptiveRateEnabled: Boolean,
    batteryTier: Int,
): Int = if (adaptiveRateEnabled && batteryTier >= 1) CameraTargetFps.FPS_30 else targetFps

/**
 * The rate the standalone (CameraX) AE target FPS range is chosen for. Same battery cap as
 * [effectiveCameraTargetFps] — it only ever LOWERS an explicit higher rate (60, DEVICE_MAX) to 30 —
 * except that [CameraTargetFps.DEVICE_DEFAULT] stays DEVICE_DEFAULT (AE range left unpinned) under
 * battery pressure too: pinning [30, 30] there would RAISE the frame-rate floor above the device's
 * own default range (often [15, 30] or lower in dim light), the opposite of saving power. The
 * ARCore session keeps [effectiveCameraTargetFps], where a 30 fps camera config is the intended cap.
 */
internal fun standaloneEffectiveCameraTargetFps(
    targetFps: Int,
    adaptiveRateEnabled: Boolean,
    batteryTier: Int,
): Int = if (targetFps == CameraTargetFps.DEVICE_DEFAULT) {
    CameraTargetFps.DEVICE_DEFAULT
} else {
    effectiveCameraTargetFps(targetFps, adaptiveRateEnabled, batteryTier)
}
