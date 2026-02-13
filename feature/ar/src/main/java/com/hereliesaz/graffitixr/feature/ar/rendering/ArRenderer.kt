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
    val slamManager: SlamManager // INJECTED: Shared Engine
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
    private val MIN_ROTATION_DEGREES = 10.0f  // 10 degrees
    private val MIN_INTERVAL_MS = 500L        // Max 2 fps for scanning to prevent buffer starvation
    private val MAX_INTERVAL_MS = 3000L       // Force a scan every 3s even if stationary

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
        // NOTE: We do NOT destroy slamManager here because it is a Singleton shared with Editor.
        // slamManager.destroy()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        try {
            backgroundRenderer.createOnGlThread(context)
            planeRenderer.createOnGlThread(context)
            pointCloudRenderer.createOnGlThread(context)

            // Re-bind native engine to new GL context if needed?
            // Since MobileGS uses mapped buffers, we usually don't need explicit context re-binding
            // unless texture IDs are lost.

            // Init buffers
            colorBitmap = Bitmap.createBitmap(COLOR_WIDTH, COLOR_HEIGHT, Bitmap.Config.ARGB_8888)
            colorBuffer = ByteBuffer.allocateDirect(COLOR_WIDTH * COLOR_HEIGHT * 4).order(ByteOrder.nativeOrder())

            isInitialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init AR renderers", e)
            isInitialized = false
        }
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

        if (!isInitialized) return
        val currentSession = session ?: return

        try {
            currentSession.setCameraTextureName(backgroundRenderer.textureId)
            val frame = currentSession.update() ?: return
            val camera = frame.camera

            backgroundRenderer.draw(frame)

            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)

            slamManager.updateCamera(viewMatrix, projectionMatrix)

            if (camera.trackingState == TrackingState.TRACKING) {
                if (showPointCloud) {
                    // Standard ARCore Debug Points
                    val pointCloud = frame.acquirePointCloud()
                    pointCloudRenderer.update(pointCloud)
                    pointCloudRenderer.draw(viewMatrix, projectionMatrix)
                    pointCloud.close()

                    planeRenderer.drawPlanes(currentSession, viewMatrix, projectionMatrix)
                }

                // SplaTAM Data Feed with Keyframing
                val currentPose = camera.pose
                val now = System.currentTimeMillis()

                if (shouldCaptureKeyframe(currentPose, now)) {
                    lastKeyframePose = currentPose
                    lastKeyframeTime = now

                    // Periodic pruning to manage memory
                    if (Math.random() < 0.05) {
                        slamManager.pruneMap(300)
                    }

                    currentPose.toMatrix(modelMatrix, 0)

                    val valY = projectionMatrix[5]
                    val fov = if (valY != 0f) (2.0 * atan(1.0 / valY)).toFloat() else 1.0f

                    processDepthAndColor(frame, modelMatrix, fov)
                }
            }

            if (showPointCloud) {
                // Render the accumulated Gaussian Splats
                slamManager.draw()
            }

            pendingCaptureCallback?.let { callback ->
                captureScreen(callback)
                pendingCaptureCallback = null
            }

        } catch (t: Throwable) {
            Log.e(TAG, "Exception on OpenGL Thread", t)
        }
    }

    private fun shouldCaptureKeyframe(currentPose: Pose, now: Long): Boolean {
        val lastPose = lastKeyframePose ?: return true
        val timeDelta = now - lastKeyframeTime

        // Rate Limiter
        if (timeDelta < MIN_INTERVAL_MS) return false
        // Timeout
        if (timeDelta > MAX_INTERVAL_MS) return true

        // Translation Delta
        val dx = currentPose.tx() - lastPose.tx()
        val dy = currentPose.ty() - lastPose.ty()
        val dz = currentPose.tz() - lastPose.tz()
        val distance = sqrt(dx*dx + dy*dy + dz*dz)

        if (distance > MIN_TRANSLATION_METERS) return true

        // Rotation Delta
        val dot = (currentPose.qx() * lastPose.qx() +
                currentPose.qy() * lastPose.qy() +
                currentPose.qz() * lastPose.qz() +
                currentPose.qw() * lastPose.qw())
        val safeDot = dot.coerceIn(-1.0f, 1.0f)
        val angleRad = 2.0f * acos(abs(safeDot))
        val angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()

        if (angleDeg > MIN_ROTATION_DEGREES) return true

        return false
    }

    private fun processDepthAndColor(frame: Frame, pose: FloatArray, fov: Float) {
        var depthImage: Image? = null
        var cameraImage: Image? = null

        try {
            depthImage = try { frame.acquireDepthImage16Bits() } catch (e: NotYetAvailableException) { null }
            cameraImage = try { frame.acquireCameraImage() } catch (e: NotYetAvailableException) { null }

            if (depthImage != null && cameraImage != null) {
                // 1. Process Color
                yuvToRgbConverter.yuvToRgb(cameraImage, colorBitmap!!)
                colorBuffer?.rewind()
                colorBitmap!!.copyPixelsToBuffer(colorBuffer!!)

                // 2. Process Depth
                val plane = depthImage.planes[0]
                val depthBuffer = plane.buffer
                val width = depthImage.width
                val height = depthImage.height
                val stride = plane.rowStride

                slamManager.feedDepthData(
                    depthBuffer = depthBuffer,
                    colorBuffer = colorBuffer,
                    width = width,
                    height = height,
                    stride = stride,
                    pose = pose,
                    fov = fov
                )
            }
        } catch (e: Exception) {
            // Log.e(TAG, "Error processing depth/color", e)
        } finally {
            // CRITICAL: Ensure images are released to prevent "cpu_image_manager" errors
            depthImage?.close()
            cameraImage?.close()
        }
    }

    fun captureFrame(callback: (Bitmap) -> Unit) {
        pendingCaptureCallback = callback
    }

    fun setFlashlight(on: Boolean) {
        val config = session?.config ?: return
        // ARCore doesn't have a direct Flashlight API in Config yet,
        // usually handled via CameraManager or specialized Config.LightEstimationMode
        // Placeholder for hardware integration
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

            // Flip Y because OpenGL is bottom-left
            val matrix = Matrix()
            matrix.preScale(1.0f, -1.0f)
            val flippedBitmap = Bitmap.createBitmap(bitmap, 0, 0, w, h, matrix, true)

            callback(flippedBitmap)
        }
    }
}