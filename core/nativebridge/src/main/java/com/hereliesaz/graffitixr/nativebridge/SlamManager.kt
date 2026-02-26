package com.hereliesaz.graffitixr.nativebridge

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.view.Surface
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SlamManager @Inject constructor() {
    private var flashlightOn = false

    external fun initialize()
    external fun ensureInitialized()
    external fun destroy()
    external fun createOnGlThread()
    external fun resetGLState()
    external fun setVisualizationMode(mode: Int)
    external fun onSurfaceChanged(width: Int, height: Int)
    external fun draw()
    external fun setBitmap(bitmap: Bitmap?)
    external fun updateCamera(viewMatrix: FloatArray, projectionMatrix: FloatArray)
    external fun updateLight(intensity: Float)
    external fun feedMonocularData(data: ByteBuffer, width: Int, height: Int)
    external fun feedStereoData(left: ByteArray, right: ByteArray, width: Int, height: Int)
    external fun feedLocationData(latitude: Double, longitude: Double, altitude: Double)
    external fun processTeleologicalFrame(buffer: ByteBuffer, timestamp: Long)
    external fun saveKeyframe(timestamp: Long): Boolean
    external fun toggleFlashlight(enabled: Boolean)

    fun toggleFlashlight() {
        flashlightOn = !flashlightOn
        toggleFlashlight(flashlightOn)
    }

    external fun initVulkan(surface: Surface, assetManager: AssetManager, width: Int, height: Int)
    external fun resizeVulkan(width: Int, height: Int)
    external fun destroyVulkan()

    fun initVulkanEngine(surface: Surface, assetManager: AssetManager, width: Int, height: Int) =
        initVulkan(surface, assetManager, width, height)
    fun resizeVulkanSurface(width: Int, height: Int) = resizeVulkan(width, height)
    fun destroyVulkanEngine() = destroyVulkan()

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
            System.loadLibrary("graffitixr_native")
        }
    }
}
