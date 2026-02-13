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

/**
 * The primary OpenGL renderer for the AR experience.
 * Manages the ARCore [Session], handles the camera background rendering,
 * and delegates SLAM/Point Cloud rendering to the native [SlamManager].
 */
class ArRenderer(private val context: Context) : GLSurfaceView.Renderer {

    private val TAG = "ArRenderer"

    // Components
    val slamManager = SlamManager()
    var session: Session? = null
        private set

    private val backgroundRenderer = BackgroundRenderer()
    private val planeRenderer = PlaneRenderer()
    private val pointCloudRenderer = PointCloudRenderer()

    // State
    var showPointCloud = true
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var isDepthSupported = false
    private var frameSkipper = 0 // Optimization to prevent CPU starvation

    // Capture
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

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        backgroundRenderer.createOnGlThread(context)

        try {
            planeRenderer.createOnGlThread(context)
            pointCloudRenderer.createOnGlThread(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init AR renderers", e)
        }

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

            if (camera.trackingState == TrackingState.TRACKING) {
                // Render Standard Visuals
                if (showPointCloud) {
                    val pointCloud = frame.acquirePointCloud()
                    pointCloudRenderer.update(pointCloud)
                    pointCloudRenderer.draw(viewMatrix, projectionMatrix)
                    pointCloud.close()

                    planeRenderer.drawPlanes(currentSession, viewMatrix, projectionMatrix)
                }

                // 4. SplaTAM Data Feed (Throttled)
                // Only process depth every 3rd frame to prevent CPU starvation
                frameSkipper++
                if (frameSkipper % 3 == 0) {
                    camera.pose.toMatrix(modelMatrix, 0)
                    val valY = projectionMatrix[5]
                    val fov = if (valY != 0f) (2.0 * atan(1.0 / valY)).toFloat() else 1.0f
                    processDepth(frame, modelMatrix, fov)
                }
            }

            // 5. Render SplaTAM Splats
            if (showPointCloud) {
                slamManager.draw()
            }

            // 6. Capture
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
                slamManager.feedDepthData(
                    depthBuffer = depthImage.planes[0].buffer,
                    colorBuffer = null,
                    width = depthImage.width,
                    height = depthImage.height,
                    stride = depthImage.planes[0].rowStride,
                    pose = pose,
                    fov = fov
                )
                depthImage.close()
            }
        } catch (e: Exception) { }
    }

    fun captureFrame(callback: (Bitmap) -> Unit) {
        pendingCaptureCallback = callback
    }

    fun setFlashlight(on: Boolean) { }

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

    private fun captureScreen(callback: (Bitmap) -> Unit) {
        val w = viewportWidth
        val h = viewportHeight
        val screenshotSize = w * h
        val buf = ByteBuffer.allocateDirect(screenshotSize * 4)
        buf.order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        Handler(Looper.getMainLooper()).post {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            buf.rewind()
            bitmap.copyPixelsFromBuffer(buf)
            val matrix = Matrix()
            matrix.preScale(1.0f, -1.0f)
            val flippedBitmap = Bitmap.createBitmap(bitmap, 0, 0, w, h, matrix, true)
            callback(flippedBitmap)
        }
    }
}
