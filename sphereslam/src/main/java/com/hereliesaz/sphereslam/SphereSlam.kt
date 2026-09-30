package com.hereliesaz.sphereslam

import com.hereliesaz.graffitixr.nativebridge.KpmBridge

/**
 * Public entry point for the native SphereSLAM sibling path.
 *
 * ARCore remains owned by feature/ar and can continue to be used exactly as before.
 */
object SphereSlam {
    fun isAvailable(): Boolean = KpmBridge.isAvailable()

    fun smokeTest(width: Int, height: Int): Boolean = KpmBridge.smokeTest(width, height)

    fun create(frameWidth: Int, frameHeight: Int): SphereSlamEngine {
        require(frameWidth > 0 && frameHeight > 0)
        return KpmSphereSlamEngine(frameWidth, frameHeight, NativeKpmApi)
    }
}
