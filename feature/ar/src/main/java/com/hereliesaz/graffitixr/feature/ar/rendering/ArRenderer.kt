package com.hereliesaz.graffitixr.feature.ar.rendering

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArRenderer(
    private val slamManager: SlamManager
) : GLSurfaceView.Renderer {

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        slamManager.initialize()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        slamManager.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // The native draw call now handles clearing the screen to transparent
        slamManager.draw()
    }

    fun updateLightEstimate(intensity: Float) {
        slamManager.updateLight(intensity)
    }

    fun setOverlay(bitmap: Bitmap?) {
        // Pass to native if needed
    }

    fun saveKeyframe(path: String) {
        slamManager.saveKeyframe(path)
    }
}