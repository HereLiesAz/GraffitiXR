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
    external fun updateCamera(viewMatrix: FloatArray, projMatrix: FloatArray)
    external fun setArCoreTrackingState(isTracking: Boolean)

    // Wrappers so MockK can intercept these in unit tests (external fun cannot be subclassed)
    fun updateAnchorTransform(transform: FloatArray) = nativeUpdateAnchorTransform(transform)
    fun setViewportSize(width: Int, height: Int) = nativeSetViewportSize(width, height)
    fun clearMap() = nativeClearMap()                       // Issue 2: reset state between projects
    fun setRelocEnabled(enabled: Boolean) = nativeSetRelocEnabled(enabled) // Issue 3

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

    private external fun nativeUpdateAnchorTransform(transform: FloatArray)
    private external fun nativeClearMap()
    private external fun nativeSetViewportSize(width: Int, height: Int)
    private external fun nativeSetRelocEnabled(enabled: Boolean)
    private external fun nativeFeedArCoreDepth(depthBuffer: ByteBuffer, width: Int, height: Int, rowStride: Int)
    private external fun nativeFeedColorFrame(colorBuffer: ByteBuffer, width: Int, height: Int)
    private external fun nativeFeedStereoData(leftBuffer: ByteBuffer, rightBuffer: ByteBuffer, width: Int, height: Int)
    private external fun nativeSaveModel(path: String)
    private external fun nativeLoadModel(path: String)
    private external fun nativeSetTargetFingerprint(descriptors: ByteArray, rows: Int, cols: Int, type: Int, points3d: FloatArray)
}