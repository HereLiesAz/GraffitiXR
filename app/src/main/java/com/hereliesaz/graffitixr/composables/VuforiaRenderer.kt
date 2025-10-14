package com.hereliesaz.graffitixr.composables

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.Log
import com.hereliesaz.graffitixr.VuforiaManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val TAG = "VuforiaRenderer"

class VuforiaRenderer(private val context: Context) : GLSurfaceView.Renderer {
    private val rendererScope = CoroutineScope(Dispatchers.Main)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.d(TAG, "onSurfaceCreated() called")
        VuforiaManager.createEngine(rendererScope)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceChanged() called with width: $width, height: $height")
        // The native rendering configuration can be handled within Vuforia's own lifecycle
    }

    override fun onDrawFrame(gl: GL10?) {
        // The native frame rendering can be handled within Vuforia's own lifecycle
    }
}
