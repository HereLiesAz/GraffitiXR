package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan

class ArRenderer(private val context: Context) : GLSurfaceView.Renderer {

    private val TAG = "ArRenderer"

    // Components
    val slamManager = SlamManager()
    var session: Session? = null
        private set

    private val backgroundRenderer = BackgroundRenderer()

    // State
    var showPointCloud = true
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var isDepthSupported = false

    // Capture State
    private var pendingCaptureCallback: ((Bitmap) -> Unit)? = null

    // Matrices
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)

    // Lifecycle
    fun onResume(owner: LifecycleOwner) {
        if (session == null) {
            try {
                session = Session(context).apply {
                    val config = config
                    config.focusMode = Config.FocusMode.AUTO
                    config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    // Check for Depth API
                    if (isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        config.depthMode = Config.DepthMode.AUTOMATIC
                        isDepthSupported = true
                    }
                    configure(config)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create AR Session", e)
                Toast.makeText(context, "AR Init Failed: ${e.message}", Toast.LENGTH_LONG).show()
                return
            }
        }

        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available", e)
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

    // GL Surface Callbacks
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        // Initialize Sub-renderers
        backgroundRenderer.createOnGlThread(context)

        // Initialize Native Engine
        slamManager.initialize()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES30.glViewport(0, 0, width, height)
        session?.setDisplayGeometry(0, width, height)
        slamManager.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val currentSession = session ?: return

        try {
            currentSession.setCameraTextureName(backgroundRenderer.textureId)
            val frame = currentSession.update() ?: return
            val camera = frame.camera

            // 1. Render Background
            backgroundRenderer.draw(frame)

            // 2. Update Camera Matrices
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)

            // 3. Update Native Engine Camera
            slamManager.updateCamera(viewMatrix, projectionMatrix)

            // 4. SplaTAM Data Feed (if tracking)
            if (camera.trackingState == TrackingState.TRACKING) {
                camera.pose.toMatrix(modelMatrix, 0)

                // Calculate vertical FOV from projection matrix (1/tan(fov/2))
                val valY = projectionMatrix[5]
                val fov = if (valY != 0f) (2.0 * atan(1.0 / valY)).toFloat() else 1.0f

                processDepth(frame, modelMatrix, fov)
            }

            // 5. Render Splats
            if (showPointCloud) {
                slamManager.draw()
            }

            // 6. Handle Screen Capture
            pendingCaptureCallback?.let { callback ->
                captureScreen(callback)
                pendingCaptureCallback = null
            }

        } catch (t: Throwable) {
            Log.e(TAG, "Exception on OpenGL Thread", t)
        }
    }

    private fun processDepth(frame: Frame, pose: FloatArray, fov: Float) {
        try {
            val depthImage = frame.acquireDepthImage16Bits()
            if (depthImage != null) {
                // SplaTAM Integration
                slamManager.feedDepthData(
                    depthBuffer = depthImage.planes[0].buffer,
                    colorBuffer = null,
                    width = depthImage.width,
                    height = depthImage.height,
                    pose = pose,
                    fov = fov
                )
                depthImage.close()
            }
        } catch (e: Exception) {
            // Depth ignored
        }
    }

    // --- Public API ---

    fun captureFrame(callback: (Bitmap) -> Unit) {
        pendingCaptureCallback = callback
    }

    fun setFlashlight(on: Boolean) {
        // Placeholder for Camera2 interop or light estimation
    }

    fun setupAugmentedImageDatabase(bitmap: Bitmap?, name: String) {
        if (bitmap == null || session == null) return

        try {
            val config = session!!.config
            val database = AugmentedImageDatabase(session)
            database.addImage(name, bitmap)
            config.augmentedImageDatabase = database
            session!!.configure(config)
            Log.i(TAG, "Augmented Image '$name' added to database")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add augmented image", e)
        }
    }

    // --- Internal Helpers ---

    private fun captureScreen(callback: (Bitmap) -> Unit) {
        val w = viewportWidth
        val h = viewportHeight
        val screenshotSize = w * h
        val buf = ByteBuffer.allocateDirect(screenshotSize * 4)
        buf.order(ByteOrder.nativeOrder())

        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)

        // Process on Main Thread to avoid stalling GL or crashing with UI callbacks
        Handler(Looper.getMainLooper()).post {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            buf.rewind()
            bitmap.copyPixelsFromBuffer(buf)

            // Flip vertically (GL origin is bottom-left, Bitmap is top-left)
            val matrix = Matrix()
            matrix.preScale(1.0f, -1.0f)
            val flippedBitmap = Bitmap.createBitmap(bitmap, 0, 0, w, h, matrix, true)

            callback(flippedBitmap)
        }
    }
}