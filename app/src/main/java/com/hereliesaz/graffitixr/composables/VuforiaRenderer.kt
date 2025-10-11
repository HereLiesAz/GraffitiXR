package com.hereliesaz.graffitixr.composables

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.Log
import com.hereliesaz.graffitixr.VuforiaJNI
import com.hereliesaz.graffitixr.VuforiaManager
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val TAG = "VuforiaRenderer"

class VuforiaRenderer(private val context: Context) : GLSurfaceView.Renderer {
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.d(TAG, "onSurfaceCreated() called")
        VuforiaManager.createEngine()
        VuforiaJNI.initRendering()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceChanged() called with width: $width, height: $height")
        val orientation = context.resources.configuration.orientation
        VuforiaJNI.configureRendering(width, height, orientation)
    }

    override fun onDrawFrame(gl: GL10?) {
        Log.d(TAG, "onDrawFrame() called")
        if (VuforiaManager.engine != 0L) {
            VuforiaJNI.renderFrame(VuforiaManager.engine)
        }
    }
}
