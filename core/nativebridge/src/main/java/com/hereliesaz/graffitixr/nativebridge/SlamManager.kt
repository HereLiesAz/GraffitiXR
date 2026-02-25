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

@Singleton
class SlamManager @Inject constructor() {

    private var nativeHandle: Long = 0
    private val isDestroyed = AtomicBoolean(true)
    private val lock = ReentrantLock()

    companion object {
        init {
            try {
                System.loadLibrary("graffitixr")
            } catch (e: UnsatisfiedLinkError) {
                try {
                    Log.e("SlamManager", "Failed to load native library 'graffitixr'", e)
                } catch (ignored: RuntimeException) {}
            } catch (e: SecurityException) {
                try {
                    Log.e("SlamManager", "SecurityException loading library", e)
                } catch (ignored: RuntimeException) {}
            }
        }
    }

    fun ensureInitialized() {
        lock.withLock {
            if (isDestroyed.get() || nativeHandle == 0L) {
                Log.d("SlamManager", "Initializing Native Engine...")
                try {
                    nativeHandle = createNativeInstance()
                    if (nativeHandle != 0L) {
                        isDestroyed.set(false)
                    } else {
                        Log.e("SlamManager", "Failed to create native engine instance.")
                    }
                } catch (e: UnsatisfiedLinkError) {
                    Log.e("SlamManager", "Native method 'createNativeInstance' not found. Library might not be loaded.", e)
                }
            }
        }
    }

    fun initialize() {
        lock.withLock { if (!isDestroyed.get()) initializeJni(nativeHandle) }
    }

    fun resetGLState() {
        lock.withLock { if (!isDestroyed.get()) resetGLStateJni(nativeHandle) }
    }

    fun updateCamera(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        lock.withLock { if (!isDestroyed.get()) updateCameraJni(nativeHandle, viewMatrix, projectionMatrix) }
    }

    fun updateLight(intensity: Float, colorCorrection: FloatArray = floatArrayOf(1f, 1f, 1f)) {
        lock.withLock { if (!isDestroyed.get()) updateLightJni(nativeHandle, intensity, colorCorrection) }
    }

    fun feedDepthData(image: Image) {
        lock.withLock {
            if (!isDestroyed.get()) {
                val plane = image.planes[0]
                feedDepthDataJni(nativeHandle, plane.buffer, image.width, image.height)
            }
        }
    }

    fun feedStereoData(leftImage: Image, rightImage: Image) {
        lock.withLock {
            if (!isDestroyed.get()) {
                val leftPlane = leftImage.planes[0]
                val rightPlane = rightImage.planes[0]
                feedStereoDataJni(
                    nativeHandle,
                    leftPlane.buffer,
                    leftImage.width,
                    leftImage.height,
                    leftPlane.rowStride,
                    rightPlane.buffer,
                    rightImage.width,
                    rightImage.height,
                    rightPlane.rowStride
                )
            }
        }
    }

    fun updateMesh(vertices: FloatArray) {
        lock.withLock { if (!isDestroyed.get()) updateMeshJni(nativeHandle, vertices) }
    }

    fun alignMap(transform: FloatArray) {
        lock.withLock { if (!isDestroyed.get()) alignMapJni(nativeHandle, transform) }
    }

    fun saveKeyframe(path: String): Boolean {
        return lock.withLock {
            if (!isDestroyed.get()) saveKeyframeJni(nativeHandle, path) else false
        }
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

    fun initVulkan(surface: Surface, assetManager: AssetManager) {
        lock.withLock { if (!isDestroyed.get()) initVulkanJni(nativeHandle, surface, assetManager) }
    }

    fun resizeVulkan(width: Int, height: Int) {
        lock.withLock { if (!isDestroyed.get()) resizeVulkanJni(nativeHandle, width, height) }
    }

    fun destroyVulkan() {
        lock.withLock { if (!isDestroyed.get()) destroyVulkanJni(nativeHandle) }
    }

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

    fun destroy() {
        lock.withLock {
            if (!isDestroyed.getAndSet(true)) {
                if (nativeHandle != 0L) {
                    destroyJni(nativeHandle)
                    nativeHandle = 0
                }
            }
        }
    }

    private external fun createNativeInstance(): Long
    private external fun destroyJni(handle: Long)
    private external fun initializeJni(handle: Long)
    private external fun resetGLStateJni(handle: Long)
    private external fun updateCameraJni(handle: Long, view: FloatArray, proj: FloatArray)
    private external fun updateLightJni(handle: Long, intensity: Float, color: FloatArray)
    private external fun feedDepthDataJni(handle: Long, buffer: ByteBuffer, width: Int, height: Int)
    private external fun feedStereoDataJni(
        handle: Long,
        leftBuffer: ByteBuffer,
        leftWidth: Int,
        leftHeight: Int,
        leftStride: Int,
        rightBuffer: ByteBuffer,
        rightWidth: Int,
        rightHeight: Int,
        rightStride: Int
    )
    private external fun updateMeshJni(handle: Long, vertices: FloatArray)
    private external fun alignMapJni(handle: Long, transform: FloatArray)
    private external fun saveKeyframeJni(handle: Long, path: String): Boolean
    private external fun setVisualizationModeJni(handle: Long, mode: Int)
    private external fun onSurfaceChangedJni(handle: Long, width: Int, height: Int)
    private external fun drawJni(handle: Long)
    private external fun loadWorldJni(handle: Long, path: String): Boolean
    private external fun saveWorldJni(handle: Long, path: String): Boolean
    private external fun initVulkanJni(handle: Long, surface: Surface, assetManager: AssetManager)
    private external fun resizeVulkanJni(handle: Long, width: Int, height: Int)
    private external fun destroyVulkanJni(handle: Long)
}