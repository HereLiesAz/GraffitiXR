package com.hereliesaz.sphereslam

import com.hereliesaz.graffitixr.nativebridge.KpmBridge

/**
 * Public entry point for GraffitiXR's native SphereSLAM/KPM path.
 *
 * On ARCore-capable devices this is a sibling relocalizer beside ARCore. On devices where ARCore
 * cannot run, the same native KPM engine is used by the CameraX standalone wall-tracking path.
 * ARCore remains owned by feature/ar and is never routed through this API.
 */
object SphereSlam {
    fun isAvailable(): Boolean = KpmBridge.isAvailable()

    fun smokeTest(width: Int, height: Int): Boolean = KpmBridge.smokeTest(width, height)

    fun create(
        frameWidth: Int,
        frameHeight: Int,
        calibration: SphereSlamCalibration,
    ): SphereSlamEngine {
        require(frameWidth > 0 && frameHeight > 0)
        return KpmSphereSlamEngine(frameWidth, frameHeight, calibration, NativeKpmApi)
    }
}
