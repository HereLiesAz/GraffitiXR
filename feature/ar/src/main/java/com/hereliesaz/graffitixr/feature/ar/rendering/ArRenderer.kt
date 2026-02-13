package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.hereliesaz.graffitixr.common.util.YuvToRgbConverter
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.sqrt

/**
 * The primary OpenGL renderer for the AR experience.
 * Manages the ARCore [Session], handles the camera background rendering,
 * and delegates SLAM/Point Cloud rendering to the shared [SlamManager].
 */
class ArRenderer(
    private val context: Context,
    val slamManager: SlamManager
) : GLSurfaceView.Renderer, DefaultLifecycleObserver {

    private val TAG = "ArRenderer"
    var session: Session? = null
        private set

    private val backgroundRenderer = BackgroundRenderer()
    private val planeRenderer = PlaneRenderer()
    private val pointCloudRenderer = PointCloudRenderer()
    private val yuvToRgbConverter = YuvToRgbConverter(context)

    // State
    var showPointCloud = true
    private var isInitialized = false
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var isDepthSupported = false

    // Keyframing State
    private var lastKeyframePose: Pose? = null
    private var lastKeyframeTime = 0L

    // Config: Minimum changes required to trigger a new scan
    private val MIN_TRANSLATION_METERS = 0.1f // 10cm
    private val MIN_ROTATION_DEGREES = 10.0f // 10 degrees
    private val MIN_INTERVAL_MS = 500L // Max 2 fps for scanning to prevent buffer starvation
    private val MAX_INTERVAL_MS = 3000L // Force a scan every 3s even if stationary

    // Capture
    private var pendingCaptureCallback: ((Bitmap) -> Unit)? = null

    // Matrices
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)

    // Buffers for Color Splats
    private val COLOR_WIDTH = 320
    private val COLOR_HEIGHT = 240
    private var colorBitmap: Bitmap? = null
    private var colorBuffer: ByteBuffer? = null

    // Lifecycle
    override fun onResume(owner: LifecycleOwner) {
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

    override fun onPause(owner: LifecycleOwner) {
        session?.pause()
    }

    fun cleanup() {
        session?.close()
        session = null
        // NOTE: We do NOT destroy slamManager here as it is shared.
    }

    // --- NEW: Flashlight Control ---
    fun setFlashlight(enable: Boolean) {
        val session = this.session ?: return
        try {
            val config = session.config
            config.flashMode = if (enable) Config.FlashMode.TORCH else Config.FlashMode.OFF
            session.configure(config)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle flashlight", e)
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        // Sub-renderers
        backgroundRenderer.createOnGlThread(context)
        planeRenderer.createOnGlThread(context)
        pointCloudRenderer.createOnGlThread(context)

        // CRITICAL: Reset the native engine's GL state because we are in a new EGLContext.
        slamManager.resetGLState()
        // Then re-initialize (compiles shaders for this context)
        slamManager.initialize()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES30.glViewport(0, 0, width, height)
        session?.setDisplayGeometry(0, width, height) // 0 = Rotation.ROTATION_0 assumption
        slamManager.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        if (session == null) return

        try {
            val frame = session!!.update()
            val camera = frame.camera

            // 1. Draw Camera Background
            backgroundRenderer.draw(frame)

            // Get Projection & View
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
            camera.getViewMatrix(viewMatrix, 0)

            // 2. Handle Tracking
            if (camera.trackingState == TrackingState.TRACKING) {

                // Draw Planes (Helper)
                planeRenderer.drawPlanes(session!!, viewMatrix, projectionMatrix)

                // Update Slam Manager Camera
                slamManager.updateCamera(viewMatrix, projectionMatrix)

                // FEED DEPTH DATA (If available)
                if (isDepthSupported) {
                    try {
                        val depthImage = frame.acquireDepthImage16Bits()
                        // FIXED: acquireCameraImage returns Image, not tryAcquire
                        val cameraImage = try { frame.acquireCameraImage() } catch (e: NotYetAvailableException) { null }

                        if (depthImage != null) {
                            val pose = camera.pose
                            // Convert pose to matrix
                            pose.toMatrix(modelMatrix, 0)

                            // Throttle scanning based on movement to save battery
                            if (shouldProcessFrame(pose)) {
                                val buffer = depthImage.planes[0].buffer
                                val width = depthImage.width
                                val height = depthImage.height
                                val stride = depthImage.planes[0].rowStride / 2 // Short (2 bytes)

                                // Process Color (Optional, expensive)
                                if (cameraImage != null) {
                                    if (colorBitmap == null) {
                                        colorBitmap = Bitmap.createBitmap(COLOR_WIDTH, COLOR_HEIGHT, Bitmap.Config.ARGB_8888)
                                        colorBuffer = ByteBuffer.allocateDirect(COLOR_WIDTH * COLOR_HEIGHT * 4)
                                    }
                                    // Resize/Convert YUV to small Bitmap for color sampling
                                    yuvToRgbConverter.yuvToRgb(cameraImage, colorBitmap!!)
                                    colorBitmap!!.copyPixelsToBuffer(colorBuffer!!)
                                    colorBuffer!!.rewind()
                                }

                                // Send to Native Engine
                                slamManager.feedDepthData(
                                    buffer,
                                    colorBuffer,
                                    width,
                                    height,
                                    stride,
                                    modelMatrix,
                                    60.0f * (3.14159f / 180f) // Approx vertical FOV in radians
                                )

                                lastKeyframePose = pose
                                lastKeyframeTime = System.currentTimeMillis()
                            }
                            depthImage.close()
                        }
                        cameraImage?.close()
                    } catch (e: Exception) {
                        // Depth/Image not ready or other error
                    }
                }

                // 3. Draw Point Cloud (Splat)
                if (showPointCloud) {
                    slamManager.draw()
                }
            }

            // Capture Hook
            if (pendingCaptureCallback != null) {
                captureFrameInternal(viewportWidth, viewportHeight, pendingCaptureCallback!!)
                pendingCaptureCallback = null
            }

        } catch (e: Exception) {
            Log.e(TAG, "Exception on render loop", e)
        }
    }

    private fun shouldProcessFrame(currentPose: Pose): Boolean {
        if (lastKeyframePose == null) return true

        val timeDelta = System.currentTimeMillis() - lastKeyframeTime
        if (timeDelta < MIN_INTERVAL_MS) return false // Rate limit
        if (timeDelta > MAX_INTERVAL_MS) return true // Force update

        // Calculate Delta
        val dist = distance(lastKeyframePose!!, currentPose)
        if (dist > MIN_TRANSLATION_METERS) return true

        // Rotation check could go here
        return false
    }

    private fun distance(p1: Pose, p2: Pose): Float {
        val dx = p1.tx() - p2.tx()
        val dy = p1.ty() - p2.ty()
        val dz = p1.tz() - p2.tz()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun captureFrame(callback: (Bitmap) -> Unit) {
        pendingCaptureCallback = callback
    }

    private fun captureFrameInternal(w: Int, h: Int, callback: (Bitmap) -> Unit) {
        val screenshotSize = w * h
        val buf = ByteBuffer.allocateDirect(screenshotSize * 4)
        buf.order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)

        Handler(Looper.getMainLooper()).post {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            buf.rewind()
            bitmap.copyPixelsFromBuffer(buf)

            // Flip Y because OpenGL is bottom-left
            val matrix = Matrix()
            matrix.preScale(1.0f, -1.0f)
            val flippedBitmap = Bitmap.createBitmap(bitmap, 0, 0, w, h, matrix, true)

            callback(flippedBitmap)
        }
    }
}