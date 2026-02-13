package com.graffitixr.feature.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.graffitixr.core.nativebridge.SlamManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArRenderer(
    private val slamManager: SlamManager
) : GLSurfaceView.Renderer {

    // FIX: Internal scope so we don't break the ArFragment constructor call
    private val rendererScope = CoroutineScope(Dispatchers.Default + Job())

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        rendererScope.launch {
            slamManager.initialize()
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)

        rendererScope.launch {
            slamManager.resize(width, height)
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // Blocking is required here to sync with GL frame
        try {
            runBlocking {
                slamManager.draw()
            }
        } catch (e: Exception) {
            Log.e("ArRenderer", "Error during draw frame: ${e.message}")
        }
    }

    fun cleanup() {
        // Stop any pending coroutines
        rendererScope.cancel()
    }
}