package com.hereliesaz.graffitixr.feature.ar.rendering

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArRenderer(
    private val slamManager: SlamManager
) : GLSurfaceView.Renderer {

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)

    init {
        Matrix.setIdentityM(projectionMatrix, 0)
        Matrix.setIdentityM(viewMatrix, 0)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        slamManager.ensureInitialized()
        slamManager.initialize()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        slamManager.onSurfaceChanged(width, height)
        val ratio = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 45f, ratio, 0.1f, 100f)
    }

    override fun onDrawFrame(gl: GL10?) {
        slamManager.updateCamera(viewMatrix, projectionMatrix)
        // The native draw call now handles clearing the screen to transparent
        slamManager.draw()
    }

    fun updateViewMatrix(matrix: FloatArray) {
        System.arraycopy(matrix, 0, viewMatrix, 0, 16)
    }

    fun updateLightEstimate(intensity: Float, colorCorrection: FloatArray = floatArrayOf(1f, 1f, 1f)) {
        slamManager.updateLight(intensity, colorCorrection)
    }

    fun setOverlay(bitmap: Bitmap?) {
        // Pass to native if needed
    }

    fun saveKeyframe(path: String) {
        slamManager.saveKeyframe(path)
    }
}