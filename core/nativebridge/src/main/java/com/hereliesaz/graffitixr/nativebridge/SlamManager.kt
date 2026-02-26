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
    private var drawLogThrottle = 0

    companion object {
        init {
            try {
                Log.e("AR_DEBUG", ">>> SlamManager: Loading native library 'graffitixr'")
                System.loadLibrary("graffitixr")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("AR_DEBUG", ">>> [FATAL] SlamManager: Failed to load native library 'graffitixr'", e)
            } catch (e: SecurityException) {
                Log.e("AR_DEBUG", ">>> [FATAL] SlamManager: SecurityException loading library", e)
            }
        }
    }

    fun ensureInitialized() {
        lock.withLock {
            if (isDestroyed.get() || nativeHandle == 0L) {
                try {
                    Log.e("AR_DEBUG", ">>> SlamManager: Creating Native Instance...")
                    nativeHandle = createNativeInstance()
                    if (nativeHandle != 0L) {
                        isDestroyed.set(false)
                        Log.e("AR_DEBUG", ">>> SlamManager: Native Instance created successfully. Handle: $nativeHandle")
                    } else {
                        Log.e("AR_DEBUG", ">>> [FATAL] SlamManager: Native Instance returned 0L handle!")
                    }
                } catch (e: UnsatisfiedLinkError) {
                    Log.e("AR_DEBUG", ">>> [FATAL] SlamManager: Native method 'createNativeInstance' not found.", e)
                }
            }
        }
    }

    // SLAM Core
    fun initialize() = lock.withLock {
        Log.e("AR_DEBUG", ">>> SlamManager: initialize() called")
        if (!isDestroyed.get()) initializeJni(nativeHandle)
    }

    fun resetGLState() = lock.withLock {
        Log.e("AR_DEBUG", ">>> SlamManager: resetGLState() called")
        if (!isDestroyed.get()) resetGLStateJni(nativeHandle)
    }

    fun updateCamera(viewMatrix: FloatArray, projectionMatrix: FloatArray) = lock.withLock {
        // We log this sparingly to prevent extreme logcat spam, but we explicitly track if the matrix is identity (failed tracking)
        if (drawLogThrottle % 60 == 0) {
            Log.e("AR_DEBUG", ">>> SlamManager: updateCamera() pushing matrices to JNI. ViewMatrix[0] = ${viewMatrix[0]}")
        }
        if (!isDestroyed.get()) updateCameraJni(nativeHandle, viewMatrix, projectionMatrix)
    }

    fun updateLight(intensity: Float, colorCorrection: FloatArray = floatArrayOf(1f, 1f, 1f)) = lock.withLock {
        if (!isDestroyed.get()) updateLightJni(nativeHandle, intensity, colorCorrection)
    }

    fun feedDepthData(image: Image) = lock.withLock { if (!isDestroyed.get()) feedDepthDataJni(nativeHandle, image.planes[0].buffer, image.width, image.height) }
    fun feedMonocularData(buffer: ByteBuffer, width: Int, height: Int) = lock.withLock { if (!isDestroyed.get()) feedMonocularDataJni(nativeHandle, buffer, width, height) }
    fun feedStereoData(leftImage: Image, rightImage: Image) = lock.withLock {
        if (!isDestroyed.get()) feedStereoDataJni(nativeHandle, leftImage.planes[0].buffer, leftImage.width, leftImage.height, leftImage.planes[0].rowStride, rightImage.planes[0].buffer, rightImage.width, rightImage.height, rightImage.planes[0].rowStride)
    }
    fun feedLocationData(latitude: Double, longitude: Double, altitude: Double) = lock.withLock {
        if (!isDestroyed.get()) feedLocationDataJni(nativeHandle, latitude, longitude, altitude)
    }
    fun alignMap(transform: FloatArray) = lock.withLock { if (!isDestroyed.get()) alignMapJni(nativeHandle, transform) }
    fun saveKeyframe(path: String) = lock.withLock { if (!isDestroyed.get()) saveKeyframeJni(nativeHandle, path) else false }

    fun onSurfaceChanged(width: Int, height: Int) = lock.withLock {
        Log.e("AR_DEBUG", ">>> SlamManager: onSurfaceChanged() W: $width, H: $height")
        if (!isDestroyed.get()) onSurfaceChangedJni(nativeHandle, width, height)
    }

    fun setVisualizationMode(mode: Int) = lock.withLock {
        Log.e("AR_DEBUG", ">>> SlamManager: setVisualizationMode() Mode: $mode")
        if (!isDestroyed.get()) setVisualizationModeJni(nativeHandle, mode)
    }

    fun draw() = lock.withLock {
        if (drawLogThrottle % 60 == 0) {
            Log.e("AR_DEBUG", ">>> SlamManager: draw() invoking Native OpenGL render pipeline")
        }
        drawLogThrottle++
        if (!isDestroyed.get()) drawJni(nativeHandle)
    }

    fun loadWorld(path: String) = lock.withLock { if (!isDestroyed.get()) loadWorldJni(nativeHandle, path) else false }
    fun saveWorld(path: String) = lock.withLock { if (!isDestroyed.get()) saveWorldJni(nativeHandle, path) else false }

    // Vulkan
    fun initVulkan(surface: Surface, assetManager: AssetManager) = lock.withLock { if (!isDestroyed.get()) initVulkanJni(nativeHandle, surface, assetManager) }
    fun resizeVulkan(width: Int, height: Int) = lock.withLock { if (!isDestroyed.get()) resizeVulkanJni(nativeHandle, width, height) }
    fun destroyVulkan() = lock.withLock { if (!isDestroyed.get()) destroyVulkanJni(nativeHandle) }

    // Sketching Tools
    fun processLiquify(bitmap: Bitmap, meshData: FloatArray) = lock.withLock { if (!isDestroyed.get()) processLiquifyJni(nativeHandle, bitmap, meshData) }
    fun processHeal(bitmap: Bitmap, mask: Bitmap) = lock.withLock { if (!isDestroyed.get()) processHealJni(nativeHandle, bitmap, mask) }
    fun processBurnDodge(bitmap: Bitmap, map: Bitmap, isBurn: Boolean) = lock.withLock { if (!isDestroyed.get()) processBurnDodgeJni(nativeHandle, bitmap, map, isBurn) }

    fun destroy() {
        lock.withLock {
            Log.e("AR_DEBUG", ">>> SlamManager: destroy() called. Terminating Native Instance.")
            if (!isDestroyed.getAndSet(true) && nativeHandle != 0L) {
                destroyJni(nativeHandle)
                nativeHandle = 0
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
    private external fun feedMonocularDataJni(handle: Long, buffer: ByteBuffer, width: Int, height: Int)
    private external fun feedStereoDataJni(handle: Long, leftBuffer: ByteBuffer, leftWidth: Int, leftHeight: Int, leftStride: Int, rightBuffer: ByteBuffer, rightWidth: Int, rightHeight: Int, rightStride: Int)
    private external fun feedLocationDataJni(handle: Long, latitude: Double, longitude: Double, altitude: Double)
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

    // Editor Tools Bindings
    private external fun processLiquifyJni(handle: Long, bitmap: Bitmap, meshData: FloatArray)
    private external fun processHealJni(handle: Long, bitmap: Bitmap, mask: Bitmap)
    private external fun processBurnDodgeJni(handle: Long, bitmap: Bitmap, map: Bitmap, isBurn: Boolean)
}