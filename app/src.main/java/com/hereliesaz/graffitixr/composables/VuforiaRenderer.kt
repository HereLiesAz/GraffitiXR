package com.hereliesaz.graffitixr.composables

import android.content.res.Configuration
import android.opengl.GLSurfaceView
import com.hereliesaz.graffitixr.VuforiaJNI
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class VuforiaRenderer : GLSurfaceView.Renderer {
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        VuforiaJNI.initRendering()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        // The orientation is passed as a placeholder for now.
        // A more robust implementation would get the actual device orientation.
        VuforiaJNI.configureRendering(width, height, Configuration.ORIENTATION_PORTRAIT)
    }

    override fun onDrawFrame(gl: GL10?) {
        VuforiaJNI.renderFrame()
    }
}
