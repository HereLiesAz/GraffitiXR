package com.hereliesaz.graffitixr.nativebridge

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.view.Surface
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class SlamManager @Inject constructor() {
    private var flashlightOn = false

    open fun initialize() = nativeInitialize()
    private external fun nativeInitialize()

    open fun ensureInitialized() = nativeEnsureInitialized()
    private external fun nativeEnsureInitialized()

    open fun destroy() = nativeDestroy()
    private external fun nativeDestroy()

    open fun createOnGlThread() = nativeCreateOnGlThread()
    private external fun nativeCreateOnGlThread()

    open fun resetGLState() = nativeResetGLState()
    private external fun nativeResetGLState()

    open fun setVisualizationMode(mode: Int) = nativeSetVisualizationMode(mode)
    private external fun nativeSetVisualizationMode(mode: Int)

    open fun onSurfaceChanged(width: Int, height: Int) = nativeOnSurfaceChanged(width, height)
    private external fun nativeOnSurfaceChanged(width: Int, height: Int)

    open fun draw() = nativeDraw()
    private external fun nativeDraw()

    open fun setBitmap(bitmap: Bitmap?) = nativeSetBitmap(bitmap)
    private external fun nativeSetBitmap(bitmap: Bitmap?)

    open fun updateCamera(viewMatrix: FloatArray, projectionMatrix: FloatArray) = nativeUpdateCamera(viewMatrix, projectionMatrix)
    private external fun nativeUpdateCamera(viewMatrix: FloatArray, projectionMatrix: FloatArray)

    open fun updateAnchorTransform(transform: FloatArray) = nativeUpdateAnchorTransform(transform)
    private external fun nativeUpdateAnchorTransform(transform: FloatArray)

    open fun updateLight(intensity: Float) = nativeUpdateLight(intensity)
    private external fun nativeUpdateLight(intensity: Float)

    open fun feedMonocularData(data: ByteBuffer, width: Int, height: Int) = nativeFeedMonocularData(data, width, height)
    private external fun nativeFeedMonocularData(data: ByteBuffer, width: Int, height: Int)

    open fun feedStereoData(left: ByteArray, right: ByteArray, width: Int, height: Int) = nativeFeedStereoData(left, right, width, height)
    private external fun nativeFeedStereoData(left: ByteArray, right: ByteArray, width: Int, height: Int)

    open fun feedLocationData(latitude: Double, longitude: Double, altitude: Double) = nativeFeedLocationData(latitude, longitude, altitude)
    private external fun nativeFeedLocationData(latitude: Double, longitude: Double, altitude: Double)

    open fun processTeleologicalFrame(buffer: ByteBuffer, timestamp: Long) = nativeProcessTeleologicalFrame(buffer, timestamp)
    private external fun nativeProcessTeleologicalFrame(buffer: ByteBuffer, timestamp: Long)

    open fun saveKeyframe(timestamp: Long): Boolean = nativeSaveKeyframe(timestamp)
    private external fun nativeSaveKeyframe(timestamp: Long): Boolean

    private external fun nativeToggleFlashlight(enabled: Boolean)

    open fun toggleFlashlight(enabled: Boolean) = nativeToggleFlashlight(enabled)

    open fun toggleFlashlight() {
        flashlightOn = !flashlightOn
        toggleFlashlight(flashlightOn)
    }

    open fun initVulkan(surface: Surface, assetManager: AssetManager, width: Int, height: Int) = nativeInitVulkan(surface, assetManager, width, height)
    private external fun nativeInitVulkan(surface: Surface, assetManager: AssetManager, width: Int, height: Int)

    open fun resizeVulkan(width: Int, height: Int) = nativeResizeVulkan(width, height)
    private external fun nativeResizeVulkan(width: Int, height: Int)

    open fun destroyVulkan() = nativeDestroyVulkan()
    private external fun nativeDestroyVulkan()

    open fun initVulkanEngine(surface: Surface, assetManager: AssetManager, width: Int, height: Int) =
        initVulkan(surface, assetManager, width, height)
    open fun resizeVulkanSurface(width: Int, height: Int) = resizeVulkan(width, height)
    open fun destroyVulkanEngine() = destroyVulkan()

    /**
     * Resets the native state to allow transitioning between OpenGL (ArView) and Vulkan (GsViewer).
     * This method should be called when destroying a surface or pausing a view.
     */
    fun reset() {
        // We attempt to reset both GL and Vulkan states to be safe.
        // In a real implementation, we might track which one is active.
        try {
            resetGLState()
        } catch (e: Exception) {
            // Ignore if GL context is not active
        }
    }

    companion object {
        init {
            // This name MUST match the add_library() call in CMake
            try {
                System.loadLibrary("graffitixr_native")
            } catch (e: UnsatisfiedLinkError) {
                // Ignore for unit tests where native library is not available
            }
        }
    }
}
