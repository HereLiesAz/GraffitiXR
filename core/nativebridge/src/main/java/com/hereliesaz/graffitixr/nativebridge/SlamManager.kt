package com.hereliesaz.graffitixr.nativebridge

import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * The JNI bridge to the C++ MobileGS engine.
 * Now configured as a state-aware relocalization watchdog.
 */
class SlamManager {

    init {
        System.loadLibrary("graffitixr")
    }

    external fun ensureInitialized()
    external fun destroy()
    external fun draw()

    external fun updateCamera(viewMatrix: FloatArray, projMatrix: FloatArray)
    external fun updateAnchorTransform(transform: FloatArray)

    // Informs the native engine to sleep its heavy PnP loops
    external fun setArCoreTrackingState(isTracking: Boolean)

    /**
     * Feeds the ARCore DEPTH16 buffer directly to the SLAM engine.
     */
    fun feedArCoreDepth(depthBuffer: ByteBuffer, width: Int, height: Int) {
        if (depthBuffer.isDirect) {
            nativeFeedArCoreDepth(depthBuffer, width, height)
        }
    }

    /**
     * Feeds the ARCore camera color frame (YUV converted to RGBA)
     */
    fun feedColorFrame(colorBuffer: ByteBuffer, width: Int, height: Int) {
        if (colorBuffer.isDirect) {
            nativeFeedColorFrame(colorBuffer, width, height)
        }
    }

    private external fun nativeFeedArCoreDepth(depthBuffer: ByteBuffer, width: Int, height: Int)
    private external fun nativeFeedColorFrame(colorBuffer: ByteBuffer, width: Int, height: Int)
}