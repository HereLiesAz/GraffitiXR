package com.hereliesaz.graffitixr.composables

import android.opengl.GLSurfaceView
import com.hereliesaz.graffitixr.VuforiaJNI
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class VuforiaRenderer : GLSurfaceView.Renderer {
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        VuforiaJNI.initRendering()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        VuforiaJNI.configureRendering(width, height, 0, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        VuforiaJNI.renderFrame()
    }
}
