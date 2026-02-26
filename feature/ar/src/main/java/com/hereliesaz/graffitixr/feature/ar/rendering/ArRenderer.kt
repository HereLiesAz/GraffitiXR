package com.hereliesaz.graffitixr.feature.ar.rendering

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import javax.inject.Inject

/**
 * ArRenderer has been deconstructed.
 * It no longer implements GLSurfaceView.Renderer because the VulkanBackend
 * handles the render loop natively. This class now acts as a state proxy.
 */
class ArRenderer @Inject constructor(
    private val slamManager: SlamManager
) {
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)

    fun updateViewMatrix(matrix: FloatArray) {
        System.arraycopy(matrix, 0, viewMatrix, 0, 16)
        slamManager.updateCamera(viewMatrix, projectionMatrix)
    }

    fun updateProjectionMatrix(matrix: FloatArray) {
        System.arraycopy(matrix, 0, projectionMatrix, 0, 16)
        slamManager.updateCamera(viewMatrix, projectionMatrix)
    }

    fun updateLightEstimate(intensity: Float, colorCorrection: FloatArray) {
        slamManager.updateLight(intensity, colorCorrection)
    }

    fun setOverlay(bitmap: Bitmap) {
        // This is a placeholder for when the native side can consume
        // a bitmap as a texture for AR projection.
    }

    /**
     * Triggered by the UI loop to request a native draw call
     * if not using continuous rendering.
     */
    fun onDrawFrame() {
        slamManager.draw()
    }
}