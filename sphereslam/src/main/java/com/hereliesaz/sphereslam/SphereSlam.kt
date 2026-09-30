package com.hereliesaz.sphereslam

import com.hereliesaz.graffitixr.nativebridge.KpmBridge

/**
 * Public entry point for the native SphereSLAM sibling path.
 *
 * ARCore remains owned by feature/ar and continues to operate independently.
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
