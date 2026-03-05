package com.hereliesaz.graffitixr.nativebridge

import android.content.res.AssetManager
import android.graphics.Bitmap
import java.nio.ByteBuffer

class SlamManager {

    init {
        System.loadLibrary("graffitixr")
    }

    external fun ensureInitialized()
    external fun initGl()
    external fun destroy()
    external fun draw()

    external fun updateCamera(viewMatrix: FloatArray, projMatrix: FloatArray)
    external fun setArCoreTrackingState(isTracking: Boolean)

    fun getSplatCount(): Int = nativeGetSplatCount()
    fun updateAnchorTransform(transform: FloatArray) = nativeUpdateAnchorTransform(transform)
    fun setViewportSize(width: Int, height: Int) = nativeSetViewportSize(width, height)
    fun clearMap() = nativeClearMap()
    fun setRelocEnabled(enabled: Boolean) = nativeSetRelocEnabled(enabled)

    fun feedArCoreDepth(depthBuffer: ByteBuffer, width: Int, height: Int, rowStride: Int) {
        if (depthBuffer.isDirect) nativeFeedArCoreDepth(depthBuffer, width, height, rowStride)
    }

    fun feedColorFrame(colorBuffer: ByteBuffer, width: Int, height: Int) {
        if (colorBuffer.isDirect) nativeFeedColorFrame(colorBuffer, width, height)
    }

    fun feedStereoData(leftBuffer: ByteBuffer, rightBuffer: ByteBuffer, width: Int, height: Int) {
        if (leftBuffer.isDirect && rightBuffer.isDirect) nativeFeedStereoData(leftBuffer, rightBuffer, width, height)
    }

    fun saveModel(path: String) { nativeSaveModel(path) }
    fun loadModel(path: String) { nativeLoadModel(path) }

    fun setTargetFingerprint(descriptors: ByteArray, rows: Int, cols: Int, type: Int, points3d: FloatArray) {
        nativeSetTargetFingerprint(descriptors, rows, cols, type, points3d)
    }

    fun loadSuperPoint(assetManager: AssetManager): Boolean = nativeLoadSuperPoint(assetManager)

    // Advanced Image Editing Tools
    fun applyLiquify(bitmap: Bitmap, points: FloatArray, radius: Float, intensity: Float) = nativeApplyLiquify(bitmap, points, radius, intensity)
    fun applyHeal(bitmap: Bitmap, points: FloatArray, radius: Float) = nativeApplyHeal(bitmap, points, radius)
    fun applyBurnDodge(bitmap: Bitmap, points: FloatArray, radius: Float, intensity: Float, isBurn: Boolean) = nativeApplyBurnDodge(bitmap, points, radius, intensity, isBurn)

    private external fun nativeGetSplatCount(): Int
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
    private external fun nativeLoadSuperPoint(assetManager: AssetManager): Boolean

    private external fun nativeApplyLiquify(bitmap: Bitmap, points: FloatArray, radius: Float, intensity: Float)
    private external fun nativeApplyHeal(bitmap: Bitmap, points: FloatArray, radius: Float)
    private external fun nativeApplyBurnDodge(bitmap: Bitmap, points: FloatArray, radius: Float, intensity: Float, isBurn: Boolean)
}