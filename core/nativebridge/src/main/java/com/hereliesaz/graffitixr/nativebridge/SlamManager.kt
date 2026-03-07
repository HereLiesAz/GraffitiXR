package com.hereliesaz.graffitixr.nativebridge

import android.content.res.AssetManager
import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.model.Fingerprint
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SlamManager @Inject constructor() {

    init {
        System.loadLibrary("graffitixr")
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

    fun updateCamera(viewMatrix: FloatArray, projectionMatrix: FloatArray, timestampNs: Long) {
        nativeUpdateCamera(viewMatrix, projectionMatrix, timestampNs)
    }

    /**
     * Feed a depth frame for map construction.
     *
     * @param depthBuffer     DEPTH16 buffer from acquireDepthImage16Bits()
     * @param width           depth image width (sensor orientation)
     * @param height          depth image height (sensor orientation)
     * @param rowStride       row stride in bytes
     * @param displayRotation Surface.ROTATION_* value (0/1/2/3) from DisplayRotationHelper.
     *                        Used to rotate the depth image from sensor orientation to
     *                        display orientation before unprojection, so it aligns with
     *                        the display-corrected view matrix from getViewMatrix().
     */
    fun feedArCoreDepth(
        depthBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        displayRotation: Int
    ) {
        if (depthBuffer.isDirect) {
            nativeFeedArCoreDepth(depthBuffer, width, height, rowStride, displayRotation)
        }
    }

    fun feedYuvFrame(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        width: Int,
        height: Int,
        yStride: Int,
        uvStride: Int,
        uvPixelStride: Int,
        timestampNs: Long
    ) {
        if (yBuffer.isDirect && uBuffer.isDirect && vBuffer.isDirect) {
            nativeFeedYuvFrame(yBuffer, uBuffer, vBuffer, width, height, yStride, uvStride, uvPixelStride, timestampNs)
        }
    }

    fun feedColorFrame(colorBuffer: ByteBuffer, width: Int, height: Int, timestampNs: Long) {
        if (colorBuffer.isDirect) {
            nativeFeedColorFrame(colorBuffer, width, height, timestampNs)
        }
    }

    fun draw() {
        nativeDraw()
    }

    fun processFrame(yuvData: ByteArray, width: Int, height: Int, poseMatrix: FloatArray, timestamp: Long): Boolean {
        return nativeProcessFrame(yuvData, width, height, poseMatrix, timestamp)
    }

    fun feedStereoData(leftBuffer: ByteBuffer, rightBuffer: ByteBuffer, width: Int, height: Int) {
        if (leftBuffer.isDirect && rightBuffer.isDirect) {
            nativeFeedStereoData(leftBuffer, rightBuffer, width, height, -1L)
        }
    }

    fun feedStereoData(leftBuffer: ByteBuffer, rightBuffer: ByteBuffer, width: Int, height: Int, timestamp: Long) {
        if (leftBuffer.isDirect && rightBuffer.isDirect) {
            nativeFeedStereoData(leftBuffer, rightBuffer, width, height, timestamp)
        }
    }

    fun setArCoreTrackingState(isTracking: Boolean) {
        nativeSetArCoreTrackingState(isTracking)
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

    fun updateLightLevel(level: Float) {
        nativeUpdateLightLevel(level)
    }

    // Native methods
    private external fun nativeInitialize()
    private external fun nativeInitGl()
    private external fun nativeSetViewportSize(width: Int, height: Int)
    private external fun nativeUpdateCamera(viewMatrix: FloatArray, projectionMatrix: FloatArray, timestampNs: Long)
    private external fun nativeUpdateLightLevel(level: Float)
    private external fun nativeDraw()
    private external fun nativeProcessFrame(yuvData: ByteArray, width: Int, height: Int, poseMatrix: FloatArray, timestamp: Long): Boolean
    private external fun nativeGetSplatCount(): Int
    private external fun nativeSetArCoreTrackingState(isTracking: Boolean)
    private external fun nativeClearMap()
    private external fun nativeSaveModel(path: String)
    private external fun nativeLoadModel(path: String)
    private external fun nativeLoadSuperPoint(assetManager: AssetManager): Boolean
    private external fun nativeUpdateAnchorTransform(transform: FloatArray)
    private external fun nativeSetRelocEnabled(enabled: Boolean)
    private external fun nativeFeedArCoreDepth(depthBuffer: ByteBuffer, width: Int, height: Int, rowStride: Int, displayRotation: Int)
    private external fun nativeFeedYuvFrame(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        width: Int,
        height: Int,
        yStride: Int,
        uvStride: Int,
        uvPixelStride: Int,
        timestampNs: Long
    )
    private external fun nativeFeedColorFrame(colorBuffer: ByteBuffer, width: Int, height: Int, timestampNs: Long)
    private external fun nativeApplyLiquify(bitmap: Bitmap, points: FloatArray, radius: Float, intensity: Float)
    private external fun nativeApplyHeal(bitmap: Bitmap, points: FloatArray, radius: Float)
    private external fun nativeApplyBurnDodge(bitmap: Bitmap, points: FloatArray, radius: Float, intensity: Float, isBurn: Boolean)
    private external fun nativeSetTargetFingerprint(descriptorsData: ByteArray, rows: Int, cols: Int, type: Int, points3d: FloatArray)
    private external fun nativeDestroy()
    private external fun nativeGenerateFingerprint(bitmap: Bitmap): Fingerprint?
    private external fun nativeFeedStereoData(leftBuffer: ByteBuffer, rightBuffer: ByteBuffer, width: Int, height: Int, timestamp: Long)
}
