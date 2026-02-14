package com.hereliesaz.graffitixr.feature.editor.rendering

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * A specialized GLRenderer for viewing 3D Gaussian Splat maps (Mockup Mode).
 * Unlike [ArRenderer], this does not use the camera feed or ARCore.
 */
class GsViewerRenderer(
    private val context: Context,
    private val mapPath: String,
    private val slamManager: SlamManager
) : GLSurfaceView.Renderer {

    val camera = VirtualCamera()
    private var isModelLoaded = false

    fun cleanup() {
        // No-op for shared slamManager
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.05f, 0.05f, 0.05f, 1.0f)

        slamManager.resetGLState()
        slamManager.initialize()

        if (mapPath.isNotEmpty()) {
            isModelLoaded = slamManager.loadWorld(mapPath)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        camera.setAspectRatio(width, height)
        slamManager.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        if (isModelLoaded) {
            slamManager.updateCamera(camera.viewMatrix, camera.projectionMatrix)
            slamManager.draw()
        }
    }

    // Input Handling Helpers
    fun onTouchDrag(dx: Float, dy: Float) {
        camera.handleDrag(dx, dy)
    }

    fun onTouchScale(factor: Float) {
        camera.handlePinch(factor)
    }

    fun onTouchPan(dx: Float, dy: Float) {
        camera.handlePan(dx, dy)
    }
}
