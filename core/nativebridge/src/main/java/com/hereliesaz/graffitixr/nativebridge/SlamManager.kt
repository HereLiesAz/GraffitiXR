package com.hereliesaz.graffitixr.nativebridge

import android.content.res.AssetManager
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JNI Bridge mapping JVM calls to the MobileGS C++ engine.
 * Enforces strict ByteBuffer zero-copy boundaries to prevent GC stutter.
 */
@Singleton
class SlamManager @Inject constructor() {

    init {
        System.loadLibrary("graffitixr_native")
    }

    // --- Core Vulkan Engine ---
    external fun initVulkanEngine(surface: Surface, assetManager: AssetManager, width: Int, height: Int): Boolean
    external fun destroyVulkanEngine()
    external fun destroyVulkan()
    external fun resizeVulkanSurface(width: Int, height: Int)
    external fun reset()
    external fun initVulkan() // Wrapper legacy fallback
    external fun resizeVulkan(width: Int, height: Int) // Wrapper legacy fallback

    // --- Frames & Sensors ---
    external fun processCameraFrame(yuvBuffer: ByteBuffer, width: Int, height: Int, stride: Int)
    external fun processDepthFrame(depthBuffer: ByteBuffer, width: Int, height: Int)
    external fun feedStereoData(leftBuffer: ByteBuffer, rightBuffer: ByteBuffer, width: Int, height: Int)
    external fun feedMonocularData(buffer: ByteBuffer, width: Int, height: Int)
    external fun feedLocationData(lat: Double, lon: Double, alt: Double, accuracy: Float)
    external fun processTeleologicalFrame(yuvBuffer: ByteBuffer, width: Int, height: Int, stride: Int)

    // --- SLAM Core ---
    external fun initialize()
    external fun saveKeyframe()
    external fun updateAnchorTransform(matrix: FloatArray)

    // --- Rendering ---
    external fun nativeDraw()
    external fun nativeSetBitmap(width: Int, height: Int, pixels: ByteBuffer)
    external fun nativeUpdateCamera(viewMatrix: FloatArray, projectionMatrix: FloatArray)
    external fun nativeUpdateLight(intensity: Float, color: FloatArray)

    // --- Voxel Point Persistence ---
    private external fun nativeGetPersistedPointBuffer(): ByteBuffer?
    external fun nativeGetPersistedPointCount(): Int

    fun getPersistedPoints(): FloatBuffer? {
        val count = nativeGetPersistedPointCount()
        if (count == 0) return null

        val buffer = nativeGetPersistedPointBuffer() ?: return null
        buffer.order(ByteOrder.nativeOrder())
        return buffer.asFloatBuffer()
    }
}
