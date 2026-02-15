package com.hereliesaz.graffitixr.nativebridge

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.media.Image
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/**
 * Manages the native MobileGS engine.
 * Handles JNI communication, thread safety, and lifecycle management.
 */
@Singleton
class SlamManager @Inject constructor() {

    private var nativeHandle: Long = 0
    private val isDestroyed = AtomicBoolean(true)
    private val lock = ReentrantLock()

    /**
     * Resurrects the native engine if it was destroyed or hasn't started.
     */
    fun ensureInitialized() {
        lock.withLock {
            if (isDestroyed.get() || nativeHandle == 0L) {
                Log.d("SlamManager", "Initializing Native Engine...")
                nativeHandle = create()
                isDestroyed.set(false)
            }
        }
    }

    // --- AR / SLAM Methods ---

    fun initialize() {
        lock.withLock { if (!isDestroyed.get()) initializeJni(nativeHandle) }
    }

    fun resetGLState() {
        lock.withLock { if (!isDestroyed.get()) resetGLStateJni(nativeHandle) }
    }

    fun updateCamera(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        lock.withLock { if (!isDestroyed.get()) updateCameraJni(nativeHandle, viewMatrix, projectionMatrix) }
    }

    fun updateLight(intensity: Float) {
        lock.withLock { if (!isDestroyed.get()) updateLightJni(nativeHandle, intensity) }
    }

    /**
     * Feeds camera image data to the native SLAM engine.
     * Assumes YUV_420_888 or similar format.
     */
    fun feedDepthData(image: Image) {
        lock.withLock {
            if (!isDestroyed.get()) {
                // Pass the Image object directly to JNI for buffer access
                feedDepthDataJni(nativeHandle, image)
            }
        }
    }

    fun updateMesh(vertices: FloatArray) {
        lock.withLock { if (!isDestroyed.get()) updateMeshJni(nativeHandle, vertices) }
    }

    fun alignMap(transform: FloatArray) {
        lock.withLock { if (!isDestroyed.get()) alignMapJni(nativeHandle, transform) }
    }

    fun saveKeyframe() {
        lock.withLock { if (!isDestroyed.get()) saveKeyframeJni(nativeHandle) }
    }

    fun setVisualizationMode(mode: Int) {
        lock.withLock { if (!isDestroyed.get()) setVisualizationModeJni(nativeHandle, mode) }
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        lock.withLock { if (!isDestroyed.get()) onSurfaceChangedJni(nativeHandle, width, height) }
    }

    fun draw() {
        lock.withLock { if (!isDestroyed.get()) drawJni(nativeHandle) }
    }

    // --- Vulkan Integration ---

    fun initVulkan(surface: Surface, assetManager: AssetManager) {
        lock.withLock {
            if (!isDestroyed.get()) {
                initVulkanJni(nativeHandle, surface, assetManager)
            }
        }
    }

    fun resizeVulkan(width: Int, height: Int) {
        lock.withLock {
            if (!isDestroyed.get()) {
                resizeVulkanJni(nativeHandle, width, height)
            }
        }
    }

    fun destroyVulkan() {
        lock.withLock {
            if (!isDestroyed.get()) {
                destroyVulkanJni(nativeHandle)
            }
        }
    }

    // --- Storage & Utils ---

    fun loadWorld(path: String): Boolean {
        return lock.withLock {
            if (!isDestroyed.get()) loadWorldJni(nativeHandle, path) else false
        }
    }

    fun saveWorld(path: String): Boolean {
        return lock.withLock {
            if (!isDestroyed.get()) saveWorldJni(nativeHandle, path) else false
        }
    }

    fun detectEdges(bitmap: Bitmap): Bitmap? {
        return lock.withLock {
            if (!isDestroyed.get()) detectEdgesJni(nativeHandle, bitmap) else null
        }
    }

    fun destroy() {
        lock.withLock {
            if (!isDestroyed.getAndSet(true)) {
                Log.d("SlamManager", "Destroying Native Engine...")
                if (nativeHandle != 0L) {
                    destroyJni(nativeHandle)
                    nativeHandle = 0
                }
            }
        }
    }

    // --- Native Signatures ---
    private external fun create(): Long
    private external fun destroyJni(handle: Long)
    private external fun initializeJni(handle: Long)
    private external fun resetGLStateJni(handle: Long)
    private external fun updateCameraJni(handle: Long, view: FloatArray, proj: FloatArray)
    private external fun updateLightJni(handle: Long, intensity: Float)
    private external fun feedDepthDataJni(handle: Long, image: Image)
    private external fun updateMeshJni(handle: Long, vertices: FloatArray)
    private external fun alignMapJni(handle: Long, transform: FloatArray)
    private external fun saveKeyframeJni(handle: Long)
    private external fun setVisualizationModeJni(handle: Long, mode: Int)
    private external fun onSurfaceChangedJni(handle: Long, width: Int, height: Int)
    private external fun drawJni(handle: Long)
    private external fun loadWorldJni(handle: Long, path: String): Boolean
    private external fun saveWorldJni(handle: Long, path: String): Boolean
    private external fun detectEdgesJni(handle: Long, bitmap: Bitmap): Bitmap?
    private external fun initVulkanJni(handle: Long, surface: Surface, assetManager: AssetManager)
    private external fun resizeVulkanJni(handle: Long, width: Int, height: Int)
    private external fun destroyVulkanJni(handle: Long)
}