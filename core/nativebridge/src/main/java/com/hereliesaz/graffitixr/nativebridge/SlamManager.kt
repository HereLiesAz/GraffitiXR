package com.hereliesaz.graffitixr.nativebridge

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

/**
 * The Unified Native Interface.
 */
class SlamManager {

    private var nativeHandle: Long = 0

    private val _mappingQuality = MutableStateFlow(0f)
    val mappingQuality: StateFlow<Float> = _mappingQuality.asStateFlow()

    companion object {
        init {
            try {
                System.loadLibrary("graffitixr")
            } catch (e: UnsatisfiedLinkError) {
                e.printStackTrace()
            }
        }
    }

    // --- Lifecycle ---

    fun initialize() {
        if (nativeHandle == 0L) {
            nativeHandle = initNativeJni()
        }
    }

    fun destroy() {
        if (nativeHandle != 0L) {
            destroyNativeJni(nativeHandle)
            nativeHandle = 0
        }
    }

    // --- Camera & Rendering ---

    fun onSurfaceChanged(width: Int, height: Int) {
        if (nativeHandle != 0L) {
            onSurfaceChangedJni(nativeHandle, width, height)
        }
    }

    fun updateCamera(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        if (nativeHandle != 0L) {
            updateCameraJni(nativeHandle, viewMatrix, projectionMatrix)
        }
    }

    fun draw() {
        if (nativeHandle != 0L) {
            drawJni(nativeHandle)
        }
    }

    // --- Data Ingestion ---

    fun feedDepthData(
        depthBuffer: ByteBuffer,
        colorBuffer: ByteBuffer?,
        width: Int,
        height: Int,
        stride: Int,
        pose: FloatArray,
        fov: Float
    ) {
        if (nativeHandle != 0L) {
            feedDepthDataJni(nativeHandle, depthBuffer, colorBuffer, width, height, stride, pose, fov)
            val count = getPointCountJni(nativeHandle)
            _mappingQuality.value = (count / 1000f).coerceIn(0f, 1f)
        }
    }

    // --- Map Management ---

    fun saveWorld(path: String): Boolean {
        if (nativeHandle == 0L) return false
        return saveWorld(nativeHandle, path)
    }

    fun loadWorld(path: String): Boolean {
        if (nativeHandle == 0L) return false
        return loadWorld(nativeHandle, path)
    }

    fun clearMap() {
        if (nativeHandle != 0L) {
            clearMapJni(nativeHandle)
        }
    }

    fun pruneMap(ageThreshold: Int) {
        if (nativeHandle != 0L) {
            pruneMapJni(nativeHandle, ageThreshold)
        }
    }

    fun setTargetDescriptors(descriptors: ByteArray, rows: Int, cols: Int, type: Int) {
        if (nativeHandle != 0L) {
            setTargetDescriptorsJni(nativeHandle, descriptors, rows, cols, type)
        }
    }

    fun alignMap(transformMatrix: FloatArray) {
        if (nativeHandle != 0L) {
            alignMapJni(nativeHandle, transformMatrix)
        }
    }

    // --- OpenCV Utilities ---

    fun extractFeatures(bitmap: Bitmap): ByteArray? {
        return extractFeaturesFromBitmap(bitmap)
    }

    fun extractFeaturesMetadata(bitmap: Bitmap): IntArray? {
        return extractFeaturesMeta(bitmap)
    }

    /**
     * Runs Canny edge detection on the input bitmap and writes the result to a new Bitmap.
     */
    fun detectEdges(bitmap: Bitmap): Bitmap? {
        if (bitmap.config != Bitmap.Config.ARGB_8888) {
            // Ensure config matches what JNI expects
            // In a real app we might copy/convert here, but for now we assume correct input
        }

        val dst = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        try {
            detectEdgesJni(bitmap, dst)
            return dst
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    // --- Native Definitions ---

    private external fun initNativeJni(): Long
    private external fun destroyNativeJni(handle: Long)
    private external fun onSurfaceChangedJni(handle: Long, width: Int, height: Int)
    private external fun updateCameraJni(handle: Long, viewMtx: FloatArray, projMtx: FloatArray)
    private external fun drawJni(handle: Long)
    private external fun feedDepthDataJni(
        handle: Long,
        depthBuffer: ByteBuffer,
        colorBuffer: ByteBuffer?,
        width: Int,
        height: Int,
        stride: Int,
        poseMatrix: FloatArray,
        fov: Float
    )
    private external fun getPointCountJni(handle: Long): Int
    private external fun saveWorld(handle: Long, path: String): Boolean
    private external fun loadWorld(handle: Long, path: String): Boolean
    private external fun clearMapJni(handle: Long)
    private external fun pruneMapJni(handle: Long, ageThreshold: Int)
    private external fun setTargetDescriptorsJni(handle: Long, descriptorBytes: ByteArray, rows: Int, cols: Int, type: Int)
    private external fun alignMapJni(handle: Long, transformMtx: FloatArray)

    // Stateless Utilities
    private external fun extractFeaturesFromBitmap(bitmap: Bitmap): ByteArray?
    private external fun extractFeaturesMeta(bitmap: Bitmap): IntArray?
    private external fun detectEdgesJni(src: Bitmap, dst: Bitmap)
}