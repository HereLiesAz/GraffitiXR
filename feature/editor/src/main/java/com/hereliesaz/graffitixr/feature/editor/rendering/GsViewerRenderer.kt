package com.hereliesaz.graffitixr.feature.editor.rendering

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.design.rendering.ProjectedImageRenderer
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
    private val layerRenderer = ProjectedImageRenderer()
    
    var activeLayer: Layer? = null
        set(value) {
            field = value
            value?.let { layerRenderer.setBitmap(it.bitmap) }
        }

    fun cleanup() {
        // No-op for shared slamManager
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.05f, 0.05f, 0.05f, 1.0f)

        slamManager.resetGLState()
        slamManager.initialize()
        // Use full Gaussian Splats for 3D Mockup mode
        slamManager.setVisualizationMode(0) // GAUSSIAN
        layerRenderer.createOnGlThread(context)

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
            val view = camera.viewMatrix
            val proj = camera.projectionMatrix
            
            slamManager.updateCamera(view, proj)
            slamManager.draw()

            // Draw Active Layer in 3D
            activeLayer?.let { layer ->
                val identity = FloatArray(16)
                Matrix.setIdentityM(identity, 0)
                layerRenderer.draw(view, proj, identity, layer)
            }
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