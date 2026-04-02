// FILE: core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/SlamManager.kt
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
    fun setSplatsVisible(visible: Boolean) = nativeSetSplatsVisible(visible)
    fun getLastDepthTrace(): String = nativeGetLastDepthTrace()
    fun getLastSplatTrace(): String = nativeGetLastSplatTrace()

    fun updateAnchorTransform(transform: FloatArray) = nativeUpdateAnchorTransform(transform)

    fun getPaintingProgress(): Float = nativeGetPaintingProgress()

    fun getAnchorTransform(): FloatArray = nativeGetAnchorTransform()

    fun addLayerFeatures(
        bitmap: Bitmap,
        depthBuffer: ByteBuffer,
        depthW: Int, depthH: Int, depthStride: Int,
        intrinsics: FloatArray,
        viewMatrix: FloatArray
    ) {
        if (depthBuffer.isDirect) {
            nativeAddLayerFeatures(bitmap, depthBuffer, depthW, depthH, depthStride, intrinsics, viewMatrix)
        }
    }

    fun setViewportSize(width: Int, height: Int) = nativeSetViewportSize(width, height)

    fun clearMap() = nativeClearMap()
    fun pruneByConfidence(threshold: Float) = nativePruneByConfidence(threshold)

    fun setRelocEnabled(enabled: Boolean) = nativeSetRelocEnabled(enabled)
    fun setVoxelSize(size: Float) = nativeSetVoxelSize(size)

    fun initGl() {
        nativeInitGl()
    }

    fun resetGlContext() {
        nativeResetGlContext()
    }

    fun updateCamera(
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray,
        mappingViewMatrix: FloatArray,
        mappingProjectionMatrix: FloatArray,
        timestampNs: Long
    ) {
        nativeUpdateCamera(viewMatrix, projectionMatrix, mappingViewMatrix, mappingProjectionMatrix, timestampNs)
    }

    fun feedArCoreDepth(
        depthBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        intrinsics: FloatArray,
        intrW: Int,
        intrH: Int
    ) {
        if (depthBuffer.isDirect) {
            nativeFeedArCoreDepth(depthBuffer, width, height, rowStride, intrinsics, intrW, intrH)
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

    fun loadModel(path: String) {
        nativeLoadModel(path)
    }

    fun importModel3D(path: String): Boolean {
        return nativeImportModel3D(path)
    }

    fun loadSuperPoint(assetManager: AssetManager): Boolean = nativeLoadSuperPoint(assetManager)

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

    fun generateFingerprintMasked(bitmap: Bitmap, mask: Bitmap?): Fingerprint? {
        return if (mask != null) nativeGenerateFingerprintMasked(bitmap, mask)
        else nativeGenerateFingerprint(bitmap)
    }

    fun annotateKeypoints(bitmap: Bitmap): Bitmap {
        val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        nativeAnnotateKeypoints(mutable)
        return mutable
    }

    fun updateLightLevel(level: Float) {
        nativeUpdateLightLevel(level)
    }

    // Native methods
    private external fun nativeInitialize()
    private external fun nativeInitGl()
    private external fun nativeResetGlContext()
    private external fun nativeSetViewportSize(width: Int, height: Int)
    private external fun nativeUpdateCamera(
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray,
        mappingViewMatrix: FloatArray,
        mappingProjectionMatrix: FloatArray,
        timestampNs: Long
    )
    private external fun nativeUpdateLightLevel(level: Float)
    private external fun nativeDraw()
    private external fun nativeGetSplatCount(): Int
    private external fun nativeSetSplatsVisible(visible: Boolean)
    private external fun nativeGetLastDepthTrace(): String
    private external fun nativeGetLastSplatTrace(): String
    private external fun nativeSetArCoreTrackingState(isTracking: Boolean)
    private external fun nativeClearMap()
    private external fun nativePruneByConfidence(threshold: Float)
    private external fun nativeSaveModel(path: String)
    private external fun nativeLoadModel(path: String)
    private external fun nativeImportModel3D(path: String): Boolean
    private external fun nativeLoadSuperPoint(assetManager: AssetManager): Boolean
    private external fun nativeUpdateAnchorTransform(transform: FloatArray)
    private external fun nativeGetAnchorTransform(): FloatArray
    private external fun nativeGetPaintingProgress(): Float
    private external fun nativeAddLayerFeatures(
        bitmap: Bitmap, depthBuffer: ByteBuffer,
        depthW: Int, depthH: Int, depthStride: Int,
        intrinsics: FloatArray, viewMatrix: FloatArray
    )
    private external fun nativeSetRelocEnabled(enabled: Boolean)
    private external fun nativeSetVoxelSize(size: Float)
    private external fun nativeFeedArCoreDepth(depthBuffer: ByteBuffer, width: Int, height: Int, rowStride: Int, intrinsics: FloatArray, intrW: Int, intrH: Int)
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
    private external fun nativeSetTargetFingerprint(descriptorsData: ByteArray, rows: Int, cols: Int, type: Int, points3d: FloatArray)
    private external fun nativeDestroy()
    private external fun nativeGenerateFingerprint(bitmap: Bitmap): Fingerprint?
    private external fun nativeGenerateFingerprintMasked(bitmap: Bitmap, mask: Bitmap): Fingerprint?
    private external fun nativeAnnotateKeypoints(bitmap: Bitmap)
    private external fun nativeFeedStereoData(leftBuffer: ByteBuffer, rightBuffer: ByteBuffer, width: Int, height: Int, timestamp: Long)
}