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

    fun setWallFingerprint(
        bitmap: Bitmap,
        mask: Bitmap?,
        depthBuffer: ByteBuffer,
        depthW: Int, depthH: Int, depthStride: Int,
        intrinsics: FloatArray,
        viewMatrix: FloatArray
    ): Fingerprint? {
        if (!depthBuffer.isDirect) return null
        return nativeSetWallFingerprint(bitmap, mask, depthBuffer, depthW, depthH, depthStride, intrinsics, viewMatrix)
    }

    fun restoreWallFingerprint(descriptorsData: ByteArray, rows: Int, cols: Int, type: Int, points3d: FloatArray) {
        nativeRestoreWallFingerprint(descriptorsData, rows, cols, type, points3d)
    }

    fun setArtworkFingerprint(
        bitmap: Bitmap,
        depthBuffer: ByteBuffer,
        depthW: Int, depthH: Int, depthStride: Int,
        intrinsics: FloatArray,
        viewMatrix: FloatArray
    ) {
        if (depthBuffer.isDirect) {
            nativeSetArtworkFingerprint(bitmap, depthBuffer, depthW, depthH, depthStride, intrinsics, viewMatrix)
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
        intrH: Int,
        cvRotateCode: Int? = null
    ) {
        if (depthBuffer.isDirect) {
            nativeFeedArCoreDepth(depthBuffer, width, height, rowStride, intrinsics, intrW, intrH, cvRotateCode ?: -1)
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
        timestampNs: Long,
        cvRotateCode: Int? = null
    ) {
        if (yBuffer.isDirect && uBuffer.isDirect && vBuffer.isDirect) {
            nativeFeedYuvFrame(yBuffer, uBuffer, vBuffer, width, height, yStride, uvStride, uvPixelStride, timestampNs, cvRotateCode ?: -1)
        }
    }

    fun feedColorFrame(colorBuffer: ByteBuffer, width: Int, height: Int, timestampNs: Long, cvRotateCode: Int? = null) {
        if (colorBuffer.isDirect) {
            nativeFeedColorFrame(colorBuffer, width, height, timestampNs, cvRotateCode ?: -1)
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

    fun setArScanMode(mode: Int) = nativeSetArScanMode(mode)
    fun setMuralMethod(method: Int) = nativeSetMuralMethod(method)

    fun getPersistentMesh(vertices: FloatArray, weights: FloatArray) = nativeGetPersistentMesh(vertices, weights)

    fun destroy() {
        if (isInitialized) {
            nativeDestroy()
            isInitialized = false
        }
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
    private external fun nativeSetArScanMode(mode: Int)
    private external fun nativeSetMuralMethod(method: Int)
    private external fun nativeGetPersistentMesh(vertices: FloatArray, weights: FloatArray)
    private external fun nativeSetWallFingerprint(
        bitmap: Bitmap, mask: Bitmap?,
        depthBuffer: ByteBuffer,
        depthW: Int, depthH: Int, depthStride: Int,
        intrinsics: FloatArray, viewMatrix: FloatArray
    ): Fingerprint?
    private external fun nativeRestoreWallFingerprint(
        descriptorsData: ByteArray, rows: Int, cols: Int, type: Int, points3d: FloatArray
    )
    private external fun nativeSetArtworkFingerprint(
        bitmap: Bitmap, depthBuffer: ByteBuffer,
        depthW: Int, depthH: Int, depthStride: Int,
        intrinsics: FloatArray, viewMatrix: FloatArray
    )
    private external fun nativeSetRelocEnabled(enabled: Boolean)
    private external fun nativeSetVoxelSize(size: Float)
    private external fun nativeFeedArCoreDepth(depthBuffer: ByteBuffer, width: Int, height: Int, rowStride: Int, intrinsics: FloatArray, intrW: Int, intrH: Int, cvRotateCode: Int)
    private external fun nativeFeedYuvFrame(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        width: Int,
        height: Int,
        yStride: Int,
        uvStride: Int,
        uvPixelStride: Int,
        timestampNs: Long,
        cvRotateCode: Int
    )
    private external fun nativeFeedColorFrame(colorBuffer: ByteBuffer, width: Int, height: Int, timestampNs: Long, cvRotateCode: Int)
    private external fun nativeDestroy()
    private external fun nativeAnnotateKeypoints(bitmap: Bitmap)
    private external fun nativeFeedStereoData(leftBuffer: ByteBuffer, rightBuffer: ByteBuffer, width: Int, height: Int, timestamp: Long)
}