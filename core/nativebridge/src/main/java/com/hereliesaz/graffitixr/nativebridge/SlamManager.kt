package com.hereliesaz.graffitixr.nativebridge

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.model.Fingerprint
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SlamManager @Inject constructor() {

    init {
        System.loadLibrary("mobilegs")
    }

    private var isInitialized = false

    fun ensureInitialized() {
        if (!isInitialized) {
            nativeInitialize()
            isInitialized = true
        }
    }

    fun getSplatCount(): Int = nativeGetSplatCount()
    fun updateAnchorTransform(transform: FloatArray) = nativeUpdateAnchorTransform(transform)
    fun setViewportSize(width: Int, height: Int) = nativeSetViewportSize(width, height)
    fun clearMap() = nativeClearMap()
    fun setRelocEnabled(enabled: Boolean) = nativeSetRelocEnabled(enabled)
    fun initGl() {
        nativeInitGl()
    }

    fun setViewportSize(width: Int, height: Int) {
        nativeSetViewportSize(width, height)
    }

    fun updateCamera(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        nativeUpdateCamera(viewMatrix, projectionMatrix)
    fun feedArCoreDepth(depthBuffer: ByteBuffer, width: Int, height: Int, rowStride: Int) {
        if (depthBuffer.isDirect) nativeFeedArCoreDepth(depthBuffer, width, height, rowStride)
    }

    fun feedColorFrame(colorBuffer: ByteBuffer, width: Int, height: Int) {
        if (colorBuffer.isDirect) nativeFeedColorFrame(colorBuffer, width, height)
    fun draw() {
        nativeDraw()
    }

    fun processFrame(yuvData: ByteArray, width: Int, height: Int, poseMatrix: FloatArray, timestamp: Long): Boolean {
        return nativeProcessFrame(yuvData, width, height, poseMatrix, timestamp)
    }

    fun feedStereoData(leftBuffer: ByteBuffer, rightBuffer: ByteBuffer, width: Int, height: Int) {
        if (leftBuffer.isDirect && rightBuffer.isDirect) nativeFeedStereoData(leftBuffer, rightBuffer, width, height)
    fun getSplatCount(): Int {
        return nativeGetSplatCount()
    }

    fun setArCoreTrackingState(isTracking: Boolean) {
        nativeSetArCoreTrackingState(isTracking)
    }

    fun clearMap() {
        nativeClearMap()
    }

    fun saveModel(path: String) {
        nativeSaveModel(path)
    }

    fun loadSuperPoint(assetManager: AssetManager): Boolean = nativeLoadSuperPoint(assetManager)
    fun loadModel(path: String) {
        nativeLoadModel(path)
    }

    // Advanced Image Editing Tools
    fun applyLiquify(bitmap: Bitmap, points: FloatArray, radius: Float, intensity: Float) = nativeApplyLiquify(bitmap, points, radius, intensity)
    fun applyHeal(bitmap: Bitmap, points: FloatArray, radius: Float) = nativeApplyHeal(bitmap, points, radius)
    fun applyBurnDodge(bitmap: Bitmap, points: FloatArray, radius: Float, intensity: Float, isBurn: Boolean) = nativeApplyBurnDodge(bitmap, points, radius, intensity, isBurn)

    private external fun nativeGetSplatCount(): Int
    private external fun nativeUpdateAnchorTransform(transform: FloatArray)
    private external fun nativeClearMap()
    fun setTargetFingerprint(descriptorsData: ByteArray, rows: Int, cols: Int, type: Int, points3d: FloatArray) {
        nativeSetTargetFingerprint(descriptorsData, rows, cols, type, points3d)
    }

    fun destroy() {
        if (isInitialized) {
            nativeDestroy()
            isInitialized = false
        }
    }

    fun generateFingerprint(bitmap: Bitmap): Fingerprint? {
        return nativeGenerateFingerprint(bitmap)
    }

    fun renderBackground(frame: com.google.ar.core.Frame) {
        // Native background rendering bypass
    }

    fun triggerKeyframe() {
        // Handled via native bridge directly if implemented
    }

    fun feedStereoData(leftData: ByteArray, rightData: ByteArray, width: Int, height: Int, timestamp: Long) {
        nativeFeedStereoData(leftData, rightData, width, height, timestamp)
    }

    private external fun nativeInitialize()
    private external fun nativeInitGl()
    private external fun nativeSetViewportSize(width: Int, height: Int)
    private external fun nativeUpdateCamera(viewMatrix: FloatArray, projectionMatrix: FloatArray)
    private external fun nativeDraw()
    private external fun nativeProcessFrame(yuvData: ByteArray, width: Int, height: Int, poseMatrix: FloatArray, timestamp: Long): Boolean
    private external fun nativeGetSplatCount(): Int
    private external fun nativeSetArCoreTrackingState(isTracking: Boolean)
    private external fun nativeClearMap()
    private external fun nativeSaveModel(path: String)
    private external fun nativeLoadModel(path: String)
    private external fun nativeSetTargetFingerprint(descriptors: ByteArray, rows: Int, cols: Int, type: Int, points3d: FloatArray)
    private external fun nativeLoadSuperPoint(assetManager: AssetManager): Boolean

    private external fun nativeApplyLiquify(bitmap: Bitmap, points: FloatArray, radius: Float, intensity: Float)
    private external fun nativeApplyHeal(bitmap: Bitmap, points: FloatArray, radius: Float)
    private external fun nativeApplyBurnDodge(bitmap: Bitmap, points: FloatArray, radius: Float, intensity: Float, isBurn: Boolean)
    private external fun nativeSetTargetFingerprint(descriptorsData: ByteArray, rows: Int, cols: Int, type: Int, points3d: FloatArray)
    private external fun nativeDestroy()
    private external fun nativeGenerateFingerprint(bitmap: Bitmap): Fingerprint?
    private external fun nativeFeedStereoData(leftData: ByteArray, rightData: ByteArray, width: Int, height: Int, timestamp: Long)
}