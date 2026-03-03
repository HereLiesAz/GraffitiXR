// FILE: core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/SlamManager.kt
package com.hereliesaz.graffitixr.nativebridge

import java.nio.ByteBuffer

class SlamManager {

    init {
        System.loadLibrary("graffitixr")
    }

    external fun ensureInitialized()
    external fun initGl()
    external fun destroy()
    external fun draw()

    external fun updateViewport(width: Int, height: Int)
    external fun setViewportSize(width: Int, height: Int)  // Issue 4: lightweight; no engine reinit
    external fun updateCamera(viewMatrix: FloatArray, projMatrix: FloatArray)
    external fun updateAnchorTransform(transform: FloatArray)
    external fun setArCoreTrackingState(isTracking: Boolean)
    external fun clearMap()                                // Issue 2: reset state between projects
    external fun setRelocEnabled(enabled: Boolean)         // Issue 3: pause reloc in non-AR modes

    fun feedArCoreDepth(depthBuffer: ByteBuffer, width: Int, height: Int, rowStride: Int) {
        if (depthBuffer.isDirect) {
            nativeFeedArCoreDepth(depthBuffer, width, height, rowStride)
        }
    }

    fun feedColorFrame(colorBuffer: ByteBuffer, width: Int, height: Int) {
        if (colorBuffer.isDirect) {
            nativeFeedColorFrame(colorBuffer, width, height)
        }
    }

    fun feedStereoData(leftBuffer: ByteBuffer, rightBuffer: ByteBuffer, width: Int, height: Int) {
        if (leftBuffer.isDirect && rightBuffer.isDirect) {
            nativeFeedStereoData(leftBuffer, rightBuffer, width, height)
        }
    }

    fun saveModel(path: String) { nativeSaveModel(path) }
    fun loadModel(path: String) { nativeLoadModel(path) }

    fun setTargetFingerprint(descriptors: ByteArray, rows: Int, cols: Int, type: Int, points3d: FloatArray) {
        nativeSetTargetFingerprint(descriptors, rows, cols, type, points3d)
    }

    private external fun nativeFeedArCoreDepth(depthBuffer: ByteBuffer, width: Int, height: Int, rowStride: Int)
    private external fun nativeFeedColorFrame(colorBuffer: ByteBuffer, width: Int, height: Int)
    private external fun nativeFeedStereoData(leftBuffer: ByteBuffer, rightBuffer: ByteBuffer, width: Int, height: Int)
    private external fun nativeSaveModel(path: String)
    private external fun nativeLoadModel(path: String)
    private external fun nativeSetTargetFingerprint(descriptors: ByteArray, rows: Int, cols: Int, type: Int, points3d: FloatArray)
}