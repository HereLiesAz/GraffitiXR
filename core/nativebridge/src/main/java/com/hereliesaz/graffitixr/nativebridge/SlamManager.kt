package com.hereliesaz.graffitixr.nativebridge

import android.graphics.Bitmap
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SlamManager @Inject constructor() {

    private var nativeHandle: Long = 0

    companion object {
        init {
            try {
                System.loadLibrary("graffitixr")
            } catch (e: UnsatisfiedLinkError) {
                // Ignore in tests or if lib isn't found
            } catch (e: SecurityException) {
                // Ignore
            }
        }
    }

    /**
     * Initializes the native engine.
     * Safe to call multiple times; internally checks if already initialized.
     */
    fun initialize() {
        if (nativeHandle == 0L) {
            nativeHandle = initNativeJni()
        }
    }

    /**
     * CRITICAL: Resets the OpenGL state (Program, VBOs) without deleting the map data.
     * Must be called when the GLSurfaceView context changes (e.g. switching screens).
     */
    fun resetGLState() {
        if (nativeHandle != 0L) {
            resetGLJni(nativeHandle)
        }
    }

    fun destroy() {
        if (nativeHandle != 0L) {
            destroyNativeJni(nativeHandle)
            nativeHandle = 0
        }
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        if (nativeHandle != 0L) onSurfaceChangedJni(nativeHandle, width, height)
    }

    fun updateCamera(viewMtx: FloatArray, projMtx: FloatArray) {
        if (nativeHandle != 0L) updateCameraJni(nativeHandle, viewMtx, projMtx)
    }

    fun updateLight(intensity: Float, r: Float, g: Float, b: Float) {
        if (nativeHandle != 0L) updateLightJni(nativeHandle, intensity, r, g, b)
    }

    fun setVisualizationMode(mode: Int) {
        if (nativeHandle != 0L) setVisualizationModeJni(nativeHandle, mode)
    }

    fun feedDepthData(depthBuffer: ByteBuffer, colorBuffer: ByteBuffer?, width: Int, height: Int, depthStride: Int, colorStride: Int, poseMtx: FloatArray, fov: Float) {
        if (nativeHandle != 0L) feedDepthDataJni(nativeHandle, depthBuffer, colorBuffer, width, height, depthStride, colorStride, poseMtx, fov)
    }

    fun draw() {
        if (nativeHandle != 0L) drawJni(nativeHandle)
    }

    fun saveWorld(path: String): Boolean {
        return if (nativeHandle != 0L) saveWorld(nativeHandle, path) else false
    }

    fun loadWorld(path: String): Boolean {
        return if (nativeHandle != 0L) loadWorld(nativeHandle, path) else false
    }

    fun importModel3D(path: String): Boolean {
        return if (nativeHandle != 0L) importModel3DJni(nativeHandle, path) else false
    }

    fun clearMap() {
        if (nativeHandle != 0L) clearMapJni(nativeHandle)
    }

    fun alignMap(transformMtx: FloatArray) {
        if (nativeHandle != 0L) alignMapJni(nativeHandle, transformMtx)
    }

    fun trainStep() {
        if (nativeHandle != 0L) trainStepJni(nativeHandle)
    }

    fun getPointCount(): Int {
        return if (nativeHandle != 0L) getPointCountJni(nativeHandle) else 0
    }

    // MISSING FUNCTION RESTORED
    fun saveKeyframe(image: Bitmap, pose: FloatArray, path: String): Boolean {
        return if (nativeHandle != 0L) saveKeyframeJni(nativeHandle, image, pose, path) else false
    }

    fun updateMesh(vertices: FloatArray) {
        if (nativeHandle != 0L) updateMeshJni(nativeHandle, vertices)
    }

    fun initVulkan(surface: android.view.Surface) {
        if (nativeHandle != 0L) initVulkanJni(nativeHandle, surface)
    }

    fun resizeVulkan(width: Int, height: Int) {
        if (nativeHandle != 0L) resizeVulkanJni(nativeHandle, width, height)
    }

    // NEW: Property to satisfy UI binding
    val mappingQuality: String
        get() {
            val count = getPointCount()
            return when {
                count < 100 -> "POOR"
                count < 1000 -> "FAIR"
                count < 5000 -> "GOOD"
                else -> "EXCELLENT"
            }
        }

    // --- OpenCV Utils ---
    fun detectEdges(bitmap: Bitmap): Bitmap? {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        detectEdgesJni(bitmap, result)
        return result
    }

    // --- Native Interface ---
    private external fun initNativeJni(): Long
    private external fun resetGLJni(handle: Long)
    private external fun destroyNativeJni(handle: Long)
    private external fun onSurfaceChangedJni(handle: Long, width: Int, height: Int)
    private external fun updateCameraJni(handle: Long, viewMtx: FloatArray, projMtx: FloatArray)
    private external fun updateLightJni(handle: Long, intensity: Float, r: Float, g: Float, b: Float)
    private external fun setVisualizationModeJni(handle: Long, mode: Int)
    private external fun feedDepthDataJni(handle: Long, depthBuffer: ByteBuffer, colorBuffer: ByteBuffer?, width: Int, height: Int, depthStride: Int, colorStride: Int, poseMtx: FloatArray, fov: Float)
    private external fun drawJni(handle: Long)
    private external fun saveWorld(handle: Long, path: String): Boolean
    private external fun loadWorld(handle: Long, path: String): Boolean
    private external fun importModel3DJni(handle: Long, path: String): Boolean
    private external fun clearMapJni(handle: Long)
    private external fun pruneMapJni(handle: Long, ageThreshold: Int)
    private external fun getPointCountJni(handle: Long): Int
    private external fun alignMapJni(handle: Long, transformMtx: FloatArray)
    // MISSING JNI DEF RESTORED
    private external fun saveKeyframeJni(handle: Long, image: Bitmap, pose: FloatArray, path: String): Boolean
    private external fun setTargetDescriptorsJni(handle: Long, descriptorBytes: ByteArray, rows: Int, cols: Int, type: Int)
    private external fun trainStepJni(handle: Long)
    private external fun updateMeshJni(handle: Long, vertices: FloatArray)
    private external fun initVulkanJni(handle: Long, surface: android.view.Surface)
    private external fun resizeVulkanJni(handle: Long, width: Int, height: Int)

    // OpenCV
    private external fun extractFeaturesFromBitmap(bitmap: Bitmap): ByteArray?
    private external fun extractFeaturesMeta(bitmap: Bitmap): IntArray?
    private external fun detectEdgesJni(srcBitmap: Bitmap, dstBitmap: Bitmap)
}