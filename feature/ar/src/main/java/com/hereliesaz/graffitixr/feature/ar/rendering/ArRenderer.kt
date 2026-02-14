package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import kotlinx.coroutines.*
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArRenderer(
    context: Context,
    private val slamManager: SlamManager
) : GLSurfaceView.Renderer {

    private val rendererScope = CoroutineScope(Dispatchers.Default + Job())
    var glSurfaceView: GLSurfaceView? = null
    var showPointCloud: Boolean = true

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        slamManager.initialize()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        slamManager.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // TODO: Implement Depth/Occlusion here.
        // 1. Get Depth Texture from ARCore.
        // 2. Pass Depth Texture to MobileGS (SlamManager) for occlusion culling.

        slamManager.draw()
    }

    fun onResume(owner: androidx.lifecycle.LifecycleOwner) {}
    fun onPause(owner: androidx.lifecycle.LifecycleOwner) {}

    fun setFlashlight(on: Boolean) {}
    fun setLayer(layer: Layer?) {}
    fun handleTap(x: Float, y: Float) {}

    fun cleanup() {
        rendererScope.cancel()
    }
}
