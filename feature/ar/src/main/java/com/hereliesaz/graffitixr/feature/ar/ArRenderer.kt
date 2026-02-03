package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Session
import com.hereliesaz.graffitixr.common.util.ImageUtils
import com.hereliesaz.graffitixr.common.model.OverlayLayer
import com.hereliesaz.graffitixr.feature.ar.rendering.BackgroundRenderer
import com.hereliesaz.graffitixr.feature.ar.rendering.ProjectedImageRenderer
import com.hereliesaz.graffitixr.feature.ar.rendering.PointCloudRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArRenderer(
    private val context: Context,
    private val sessionAccessor: () -> Session?
) : GLSurfaceView.Renderer {

    private val backgroundRenderer = BackgroundRenderer()
    private val pointCloudRenderer = PointCloudRenderer()

    // FIX: ConcurrentHashMap to prevent crashes during iteration
    private val layerRenderers = ConcurrentHashMap<String, ProjectedImageRenderer>()

    private var currentLayers: List<OverlayLayer> = emptyList()

    // View/Projection matrices
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)

    // Viewport
    private var viewportWidth = 0
    private var viewportHeight = 0

    // State flags
    var showPointCloud = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        backgroundRenderer.createOnGlThread(context)
        pointCloudRenderer.createOnGlThread(context)

        // Initialize existing layer renderers if any (unlikely on create, but safe)
        layerRenderers.values.forEach { it.createOnGlThread(context) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
        sessionAccessor()?.setDisplayGeometry(0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val session = sessionAccessor() ?: return
        if (viewportWidth == 0 || viewportHeight == 0) return

        try {
            val frame = session.update()
            val camera = frame.camera

            // Draw Background
            backgroundRenderer.draw(frame)

            // Update Matrices
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)

            // Draw Point Cloud
            if (showPointCloud) {
                // In a real app, you'd get points from frame.acquirePointCloud()
                // pointCloudRenderer.update(frame.acquirePointCloud())
                pointCloudRenderer.draw(viewMatrix, projectionMatrix)
            }

            // Draw Projected Layers
            // Safe iteration with ConcurrentHashMap
            layerRenderers.values.forEach { renderer ->
                renderer.draw(viewMatrix, projectionMatrix)
            }

        } catch (e: Exception) {
            Log.e("ArRenderer", "Exception on draw frame", e)
        }
    }

    fun updateLayers(newLayers: List<OverlayLayer>) {
        currentLayers = newLayers

        // 1. Add new renderers
        newLayers.forEach { layer ->
            if (!layerRenderers.containsKey(layer.id)) {
                val renderer = ProjectedImageRenderer()
                // Initialize GL resources for the new renderer
                // Note: In a real scenario, we need to ensure this runs on GL thread
                // or the renderer handles lazy init. Assuming renderer handles lazy init inside draw().
                // However, loading bitmap is async.

                CoroutineScope(Dispatchers.IO).launch {
                    val bmp = ImageUtils.loadBitmapFromUri(context, layer.uri)
                    if (bmp != null) {
                        renderer.setBitmap(bmp)
                        renderer.updateTransforms(layer.scale, layer.x, layer.y, layer.rotation)
                        renderer.setOpacity(layer.opacity)
                    }
                }
                layerRenderers[layer.id] = renderer
            } else {
                // Update existing
                val renderer = layerRenderers[layer.id]
                renderer?.updateTransforms(layer.scale, layer.x, layer.y, layer.rotation)
                renderer?.setOpacity(layer.opacity)
            }
        }

        // 2. Remove stale renderers
        val newIds = newLayers.map { it.id }.toSet()
        val iterator = layerRenderers.keys.iterator()
        while (iterator.hasNext()) {
            if (!newIds.contains(iterator.next())) {
                iterator.remove()
            }
        }
    }
}