package com.hereliesaz.graffitixr.composables

import android.content.Context
import android.content.res.Configuration
import android.opengl.GLSurfaceView
import com.hereliesaz.graffitixr.VuforiaJNI
import com.hereliesaz.graffitixr.VuforiaManager
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class VuforiaRenderer(private val context: Context) : GLSurfaceView.Renderer {
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        VuforiaJNI.initRendering()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        val orientation = context.resources.configuration.orientation
        VuforiaJNI.configureRendering(width, height, orientation)
    }

    override fun onDrawFrame(gl: GL10?) {
        val engine = VuforiaManager.getEngine()
        if (engine != 0L) {
            VuforiaJNI.renderFrame(engine)
        }
    }
}
