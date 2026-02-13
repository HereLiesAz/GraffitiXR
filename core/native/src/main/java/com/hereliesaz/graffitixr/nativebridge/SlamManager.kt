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
     * Pass depth and color data to the SplaTAM engine.
     * * @param depthBuffer Direct ByteBuffer containing depth data (Float, Meters)
     * @param colorBuffer Direct ByteBuffer containing RGB data (Float 0..1 or Byte 0..255)
     * @param width Width of the buffers
     * @param height Height of the buffers
     * @param pose 4x4 Model Matrix of the Camera (Column-Major)
     * @param fov Vertical Field of View in Radians
     */
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

    // UPDATED SIGNATURE
    private external fun feedDepthDataJni(
        handle: Long,
        depthBuffer: ByteBuffer,
        colorBuffer: ByteBuffer?,
        width: Int,
        height: Int,
        stride: Int,
        pose: FloatArray,
        fov: Float
    )

    private external fun drawJni(handle: Long)
    private external fun getPointCountJni(handle: Long): Int
    private external fun onSurfaceChangedJni(handle: Long, width: Int, height: Int)
    private external fun saveWorld(handle: Long, path: String): Boolean
    private external fun loadWorld(handle: Long, path: String): Boolean
    private external fun alignMapJni(handle: Long, transformMtx: FloatArray)
    private external fun clearMapJni(handle: Long)

    // --- Legacy Compatibility Layer (Deprecated) ---
    fun init(assetManager: AssetManager) = initialize()
    fun getExternalTextureId(): Int = 0
    fun update(timestampNs: Long, position: FloatArray, rotation: FloatArray) { }
    fun draw(renderPointCloud: Boolean) = draw()
    fun reset() { destroy(); initialize() }
}