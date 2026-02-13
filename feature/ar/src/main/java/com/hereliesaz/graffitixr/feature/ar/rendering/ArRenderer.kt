package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.util.YuvToRgbConverter
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
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
    private val projectedImageRenderer = ProjectedImageRenderer()
    private val yuvToRgbConverter = YuvToRgbConverter(context)

    // State
    var showPointCloud = true
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var isDepthSupported = false
    private var isResumed = false
    private var isCameraTextureInitialized = false

    private val tapQueue = ConcurrentLinkedQueue<PointF>()

    // Anchor State
    private var anchor: Anchor? = null
    private var activeLayer: Layer? = null

    // Matrix Helpers
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)

    // Slam Throttling
    private var lastKeyframePose: Pose? = null
    private var lastKeyframeTime = 0L
    private val MIN_INTERVAL_MS = 100L
    private val MAX_INTERVAL_MS = 2000L
    private val MIN_TRANSLATION_METERS = 0.05f

    // Color processing
    private var colorBitmap: Bitmap? = null
    private var colorBuffer: ByteBuffer? = null
    private val COLOR_WIDTH = 640
    private val COLOR_HEIGHT = 480

    // Capture Hook
    private var pendingCaptureCallback: ((Bitmap) -> Unit)? = null

    // Surface reference
    var glSurfaceView: GLSurfaceView? = null

    override fun onResume(owner: LifecycleOwner) {
        isResumed = true
    }

    override fun onPause(owner: LifecycleOwner) {
        isResumed = false
    }

    fun setSession(session: Session) {
        this.session = session
        val config = session.config
        isDepthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        if (isDepthSupported) {
            config.depthMode = Config.DepthMode.AUTOMATIC
        }
        config.focusMode = Config.FocusMode.FIXED
        config.lightEstimationMode = Config.LightEstimationMode.DISABLED
        session.configure(config)
    }

    fun setFlashlight(enabled: Boolean) {
        // Implementation depends on ARCore version and device support.
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        try {
            // Sub-renderers
            backgroundRenderer.createOnGlThread(context)
            planeRenderer.createOnGlThread(context)
            pointCloudRenderer.createOnGlThread(context)
            projectedImageRenderer.createOnGlThread(context)

            // Mark as needs initialization since we have a texture ID now
            isCameraTextureInitialized = false

            // Reset the native engine's GL state because we are in a new EGLContext.
            slamManager.resetGLState()
            // Then re-initialize (compiles shaders for this context)
            slamManager.initialize()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize GL components", e)
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

        if (!isResumed) return
        val currentSession = session ?: return

        // CRITICAL FIX: Ensure texture is bound before updating.
        if (!isCameraTextureInitialized) {
            val texId = backgroundRenderer.textureId
            if (texId != -1) {
                currentSession.setCameraTextureName(texId)
                isCameraTextureInitialized = true
            } else {
                return // Texture not ready yet
            }
        }

        try {
            // Re-check isResumed right before update to minimize race window
            if (!isResumed) return

            val frame = currentSession.update()
            val camera = frame.camera

            // 1. Draw Camera Background
            backgroundRenderer.draw(frame)

            // Get Projection & View
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
            camera.getViewMatrix(viewMatrix, 0)

            // 2. Handle Tracking
            if (camera.trackingState == TrackingState.TRACKING) {
                // Draw Planes (Helper)
                planeRenderer.drawPlanes(currentSession, viewMatrix, projectionMatrix, camera.pose)

                // Draw Anchored Image
                anchor?.let {
                    if (it.trackingState == TrackingState.TRACKING && activeLayer != null) {
                        projectedImageRenderer.draw(viewMatrix, projectionMatrix, it, activeLayer!!)
                    }
                }

                // Handle Taps
                val tap = tapQueue.poll()
                if (tap != null) {
                    handleTapInternal(frame, tap.x, tap.y)
                }

                try {
                    // Update Slam Manager Camera
                    slamManager.updateCamera(viewMatrix, projectionMatrix)

                    // FEED DEPTH DATA (If available)
                    if (isDepthSupported) {
                        processDepth(frame)
                    }

                    // 3. Draw Point Cloud (Splat)
                    if (showPointCloud) {
                        slamManager.draw()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in native SLAM rendering", e)
                }
            }

            // Capture Hook
            if (pendingCaptureCallback != null) {
                captureFrameInternal(viewportWidth, viewportHeight, pendingCaptureCallback!!)
                pendingCaptureCallback = null
            }

        } catch (e: SessionPausedException) {
            Log.w(TAG, "Session paused during update", e)
        } catch (e: Exception) {
            if (Math.random() < 0.05) Log.e(TAG, "Exception on render loop", e)
        }
    }

    private fun processDepth(frame: Frame) {
        var depthImage: android.media.Image? = null
        var cameraImage: android.media.Image? = null
        
        try {
            depthImage = try { frame.acquireDepthImage16Bits() } catch (e: Exception) { null }
            cameraImage = try { frame.acquireCameraImage() } catch (e: Exception) { null }

            depthImage?.let { dImg ->
                if (dImg.planes.isNotEmpty() && dImg.planes[0].buffer != null) {
                    val camera = frame.camera
                    val pose = camera.pose

                    if (shouldProcessFrame(pose)) {
                        pose.toMatrix(modelMatrix, 0)
                        val buffer = dImg.planes[0].buffer
                        val width = dImg.width
                        val height = dImg.height
                        val stride = dImg.planes[0].rowStride / 2

                        cameraImage?.let { cImg ->
                            if (colorBitmap == null) {
                                colorBitmap = Bitmap.createBitmap(COLOR_WIDTH, COLOR_HEIGHT, Bitmap.Config.ARGB_8888)
                                colorBuffer = ByteBuffer.allocateDirect(COLOR_WIDTH * COLOR_HEIGHT * 4)
                            }
                            yuvToRgbConverter.yuvToRgb(cImg, colorBitmap!!)
                            colorBitmap!!.copyPixelsToBuffer(colorBuffer!!)
                            colorBuffer!!.rewind()
                        }

                        slamManager.feedDepthData(
                            buffer,
                            colorBuffer,
                            width,
                            height,
                            stride,
                            COLOR_WIDTH * 4,
                            modelMatrix,
                            60.0f * (3.14159f / 180f)
                        )

                        lastKeyframePose = pose
                        lastKeyframeTime = System.currentTimeMillis()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process depth data", e)
        } finally {
            depthImage?.close()
            cameraImage?.close()
        }
    }

    private fun shouldProcessFrame(currentPose: Pose): Boolean {
        if (lastKeyframePose == null) return true

        val timeDelta = System.currentTimeMillis() - lastKeyframeTime
        if (timeDelta < MIN_INTERVAL_MS) return false
        if (timeDelta > MAX_INTERVAL_MS) return true

        val dist = distance(lastKeyframePose!!, currentPose)
        return dist > MIN_TRANSLATION_METERS
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
        if (w <= 0 || h <= 0) return
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)

        Handler(Looper.getMainLooper()).post {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buf.rewind())
            val matrix = Matrix().apply { postScale(1f, -1f) }
            callback(Bitmap.createBitmap(bitmap, 0, 0, w, h, matrix, true))
        }
    }

    fun handleTap(x: Float, y: Float) {
        tapQueue.offer(PointF(x, y))
    }

    private fun handleTapInternal(frame: Frame, x: Float, y: Float) {
        val hits = frame.hitTest(x, y)
        for (hit in hits) {
            val trackable = hit.trackable
            if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                // Check color (orientation/distance)
                val color = planeRenderer.calculatePlaneColor(trackable, frame.camera.pose)
                if (color[1] > 0.8f && color[2] < 0.2f) {
                    Log.d(TAG, "Valid Surface Selected")
                    anchor?.detach()
                    anchor = trackable.createAnchor(hit.hitPose)

                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "Surface Selected", Toast.LENGTH_SHORT).show()
                    }
                    break
                }
            }
        }
    }

    fun setLayer(layer: Layer?) {
        activeLayer = layer
        if (layer != null) {
            projectedImageRenderer.setBitmap(layer.bitmap)
        }
    }
    
    fun cleanup() {
        // Handle cleanup
    }
}
