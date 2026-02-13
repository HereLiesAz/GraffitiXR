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
 * It uses a [VirtualCamera] to navigate the 3D scene.
 */
class GsViewerRenderer(
    private val context: Context,
    private val mapPath: String,
    private val slamManager: SlamManager // INJECTED: Shared Engine
) : GLSurfaceView.Renderer {

    // Camera
    val camera = VirtualCamera()

    // State
    private var isModelLoaded = false

    fun cleanup() {
        // We do NOT destroy the shared slamManager here.
        // But we might want to clear the specific view state if needed.
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.05f, 0.05f, 0.05f, 1.0f) // Dark grey background

        // CRITICAL: Reset Native GL State for this new Surface Context
        slamManager.resetGLState()
        // Re-initialize engine shaders
        slamManager.initialize()

        // Load the model immediately on startup
        if (mapPath.isNotEmpty()) {
            // Note: loading might block, better off-thread in real app, but ok for now
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
            // 1. Push Virtual Camera Matrices to Engine
            slamManager.updateCamera(camera.viewMatrix, camera.projectionMatrix)

            // 2. Render shared point cloud
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