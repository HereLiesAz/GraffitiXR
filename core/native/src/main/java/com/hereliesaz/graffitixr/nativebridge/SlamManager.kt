package com.hereliesaz.graffitixr.nativebridge

import android.content.res.AssetManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

/**
 * JNI Bridge to the MobileGS C++ Engine.
 * Synchronized with GraffitiJNI.cpp signatures.
 */
class SlamManager {

    companion object {
        init {
            System.loadLibrary("graffitixr")
        }
    }

    private var nativeHandle: Long = 0

    private val _mappingQuality = MutableStateFlow(0f)
    val mappingQuality: StateFlow<Float> = _mappingQuality.asStateFlow()

    /**
     * Initialize the native engine and return a pointer handle.
     */
    fun initialize() {
        if (nativeHandle == 0L) {
            nativeHandle = initNativeJni()
        }
    }

    /**
     * Clean up native resources.
     */
    fun destroy() {
        if (nativeHandle != 0L) {
            destroyNativeJni(nativeHandle)
            nativeHandle = 0L
        }
    }

    /**
     * Update camera matrices.
     */
    fun updateCamera(viewMtx: FloatArray, projMtx: FloatArray) {
        if (nativeHandle != 0L) {
            updateCameraJni(nativeHandle, viewMtx, projMtx)
            // Update mapping quality metric based on point density
            val points = getPointCountJni(nativeHandle)
            _mappingQuality.value = (points / 2000f).coerceAtMost(1.0f)
        }
    }

    /**
     * Pass the raw 16-bit depth buffer from ARCore to the native engine.
     * This is critical for the "Neural Scan" feature, which uses depth data for
     * sparse voxel hashing and unprojection.
     *
     * @param buffer Direct ByteBuffer containing 16-bit depth values (mm).
     * @param width Width of the depth image.
     * @param height Height of the depth image.
     */
    fun feedDepthData(buffer: ByteBuffer, width: Int, height: Int, stride: Int) {
        if (nativeHandle != 0L) {
            feedDepthDataJni(nativeHandle, buffer, width, height, stride)
        }
    }

    /**
     * Trigger OpenGL draw call.
     */
    fun draw() {
        if (nativeHandle != 0L) {
            drawJni(nativeHandle)
        }
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        if (nativeHandle != 0L) {
            onSurfaceChangedJni(nativeHandle, width, height)
        }
    }

    fun saveWorld(path: String): Boolean {
        return if (nativeHandle != 0L) saveWorld(nativeHandle, path) else false
    }

    fun loadWorld(path: String): Boolean {
        return if (nativeHandle != 0L) loadWorld(nativeHandle, path) else false
    }

    fun alignMap(transformMtx: FloatArray) {
        if (nativeHandle != 0L) alignMapJni(nativeHandle, transformMtx)
    }

    fun clearMap() {
        if (nativeHandle != 0L) {
            clearMapJni(nativeHandle)
            _mappingQuality.value = 0f
        }
    }

    // --- Native JNI declarations (Private) ---

    private external fun initNativeJni(): Long
    private external fun destroyNativeJni(handle: Long)
    private external fun updateCameraJni(handle: Long, viewMtx: FloatArray, projMtx: FloatArray)
    private external fun feedDepthDataJni(handle: Long, buffer: ByteBuffer, width: Int, height: Int, stride: Int)
    private external fun drawJni(handle: Long)
    private external fun getPointCountJni(handle: Long): Int
    private external fun onSurfaceChangedJni(handle: Long, width: Int, height: Int)
    private external fun saveWorld(handle: Long, path: String): Boolean
    private external fun loadWorld(handle: Long, path: String): Boolean
    private external fun alignMapJni(handle: Long, transformMtx: FloatArray)
    private external fun clearMapJni(handle: Long)

    // --- Legacy Compatibility Layer (TO BE DEPRECATED) ---

    fun init(assetManager: AssetManager) = initialize()
    
    fun getExternalTextureId(): Int = 0 // Handled by ARCore directly now

    fun update(timestampNs: Long, position: FloatArray, rotation: FloatArray) {
        // Placeholder: Real integration uses updateCamera with matrices
    }

    fun draw(renderPointCloud: Boolean) = draw()

    fun reset() {
        destroy()
        initialize()
    }
}