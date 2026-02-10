package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import android.view.View
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.PointCloud
import com.google.ar.core.Session
import com.google.ar.core.exceptions.NotYetAvailableException
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArRenderer(private val context: Context) : GLSurfaceView.Renderer {

    // Renderers
    private val backgroundRenderer = BackgroundRenderer()
    private val pointCloudRenderer = PointCloudRenderer()
    val slamManager = SlamManager() // Use val and exposed

    // Matrices
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)

    // Configuration
    var showPointCloud: Boolean = true

    var session: Session? = null
        private set

    // Viewport
    private var viewportWidth = 0
    private var viewportHeight = 0

    // Capture callback
    private var pendingCaptureCallback: ((Bitmap) -> Unit)? = null

    val view: View get() = GLSurfaceView(context).apply {
        setPreserveEGLContextOnPause(true)
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(this@ArRenderer)
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    fun setSession(session: Session) {
        this.session = session
    }

    fun setupAugmentedImageDatabase(bitmap: Bitmap, name: String) {
        val currentSession = session ?: return
        val database = AugmentedImageDatabase(currentSession)
        database.addImage(name, bitmap)
        val config = currentSession.config
        config.augmentedImageDatabase = database
        currentSession.configure(config)
    }

    fun setFlashlight(enabled: Boolean) {
        val currentSession = session ?: return
        val config = currentSession.config
        // Assuming ARCore supports it or ignored for now.
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        try {
            backgroundRenderer.createOnGlThread()
            pointCloudRenderer.createOnGlThread(context)
            slamManager.initialize()
        } catch (e: Exception) {
            Log.e("ArRenderer", "Failed to init GL", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
        session?.setDisplayGeometry(0, width, height)
        slamManager.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val currentSession = session ?: return

        try {
            // Notify ARCore that camera texture is ready
            currentSession.setCameraTextureName(backgroundRenderer.textureId)

            val frame = currentSession.update()
            val camera = frame.camera

            // Draw Background
            backgroundRenderer.draw(frame)

            // Projection Matrix
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
            camera.getViewMatrix(viewMatrix, 0)

            // Update SLAM
            slamManager.updateCamera(viewMatrix, projectionMatrix)

            // Acquire Depth Image
            try {
                 val depthImage = frame.acquireDepthImage16Bits() // ARCore 1.12+
                 if (depthImage != null) {
                     val planes = depthImage.planes
                     if (planes.isNotEmpty()) {
                         val buffer = planes[0].buffer
                         val width = depthImage.width
                         val height = depthImage.height
                         slamManager.feedDepthData(buffer, width, height)
                     }
                     depthImage.close()
                 }
            } catch (e: NotYetAvailableException) {
                // Ignore
            } catch (e: Exception) {
                // Log.e("ArRenderer", "Error processing depth", e)
            }

            // Draw Custom Engine (SLAM)
            slamManager.draw()

            // Draw Point Cloud (ARCore Debug)
            if (showPointCloud) {
                val pointCloud = frame.acquirePointCloud()
                pointCloudRenderer.update(pointCloud)
                pointCloudRenderer.draw(viewMatrix, projectionMatrix)
                pointCloud.release()
            }

            // Handle Capture
            pendingCaptureCallback?.let { callback ->
                doCapture(callback)
                pendingCaptureCallback = null
            }

        } catch (t: Throwable) {
            Log.e("ArRenderer", "Exception on draw frame", t)
        }
    }

    fun captureFrame(onBitmapCaptured: (Bitmap) -> Unit) {
        pendingCaptureCallback = onBitmapCaptured
    }

    private fun doCapture(callback: (Bitmap) -> Unit) {
        val w = viewportWidth
        val h = viewportHeight
        if (w <= 0 || h <= 0) return

        val pixelBuffer = IntArray(w * h)
        val bitmapBuffer = IntBuffer.wrap(pixelBuffer)
        bitmapBuffer.position(0)

        // Read pixels from framebuffer
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bitmapBuffer)

        try {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(IntBuffer.wrap(pixelBuffer))

            // OpenGL uses bottom-left origin, Bitmap uses top-left. Flip it vertically.
            val matrix = android.graphics.Matrix()
            matrix.postScale(1f, -1f) // Flip Y

            val flippedBitmap = Bitmap.createBitmap(bitmap, 0, 0, w, h, matrix, true)
            callback(flippedBitmap)

        } catch (e: Exception) {
            Log.e("ArRenderer", "Capture failed", e)
        }
    }

    fun onResume(owner: LifecycleOwner) {
        try {
            session?.resume()
        } catch (e: Exception) {
            Log.e("ArRenderer", "Failed to resume session", e)
        }
    }

    fun onPause(owner: LifecycleOwner) {
        session?.pause()
    }

    fun cleanup() {
        session?.close()
        session = null
        slamManager.destroy()
    }
}
