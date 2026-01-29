package com.hereliesaz.graffitixr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.hereliesaz.graffitixr.data.Fingerprint
import com.hereliesaz.graffitixr.data.OverlayLayer
import com.hereliesaz.graffitixr.rendering.BackgroundRenderer
import com.hereliesaz.graffitixr.rendering.PlaneRenderer
import com.hereliesaz.graffitixr.rendering.ProjectedImageRenderer
import com.hereliesaz.graffitixr.slam.SlamManager
import com.hereliesaz.graffitixr.utils.DisplayRotationHelper
import com.hereliesaz.graffitixr.utils.YuvToRgbConverter
import com.hereliesaz.graffitixr.utils.generateFingerprint
import java.io.IOException
import java.util.Collections
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArRenderer(
    private val context: Context,
    private val onPlanesDetected: (Boolean) -> Unit,
    private val onFrameCaptured: (Bitmap) -> Unit,
    private val onProgressUpdated: (Float, Bitmap?) -> Unit,
    private val onTrackingFailure: (String?) -> Unit,
    private val onBoundsUpdated: (RectF) -> Unit
) : GLSurfaceView.Renderer {

    var session: Session? = null
    private var displayRotationHelper: DisplayRotationHelper = DisplayRotationHelper(context)

    // Renderers
    private val backgroundRenderer = BackgroundRenderer()
    private val planeRenderer = PlaneRenderer()
    private val imageRenderer = ProjectedImageRenderer()

    // Tools
    private val yuvConverter = YuvToRgbConverter(context)
    private var captureBitmap: Bitmap? = null
    private val captureLock = Any() // Thread safety for capture bitmap

    val slamManager = SlamManager()

    var onSessionUpdated: ((Session, Frame) -> Unit)? = null
    var onAnchorCreated: ((Anchor) -> Unit)? = null

    var showMiniMap = false
    var showGuide = false

    private var viewWidth = 0
    private var viewHeight = 0
    private var isFlashlightOn = false

    @Volatile
    private var captureNextFrame = false

    private var layers: List<OverlayLayer> = emptyList()
    private val queuedTaps = Collections.synchronizedList(ArrayList<QueuedTap>())

    data class QueuedTap(val x: Float, val y: Float)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        try {
            backgroundRenderer.createOnGlThread()
            planeRenderer.createOnGlThread()
            imageRenderer.createOnGlThread()
            slamManager.initNative()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        displayRotationHelper.onSurfaceChanged(width, height)
        GLES30.glViewport(0, 0, width, height)
        slamManager.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        if (session == null) return

        displayRotationHelper.updateSessionIfNeeded(session!!)

        try {
            session!!.setCameraTextureName(backgroundRenderer.textureId)
            val frame = session!!.update()
            val camera = frame.camera

            onSessionUpdated?.invoke(session!!, frame)

            handleTaps(frame)
            handleCapture(frame)

            // 1. Draw Camera Background
            backgroundRenderer.draw(frame)

            // Get Matrices
            val projmtx = FloatArray(16)
            camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f)
            val viewmtx = FloatArray(16)
            camera.getViewMatrix(viewmtx, 0)

            val trackingState = camera.trackingState

            if (trackingState == TrackingState.TRACKING) {
                onTrackingFailure(null)

                // 2. Draw Planes (Grid)
                val planes = session!!.getAllTrackables(Plane::class.java)
                val hasPlanes = planes.any { it.trackingState == TrackingState.TRACKING }
                onPlanesDetected(hasPlanes)

                if (hasPlanes) {
                    planeRenderer.drawPlanes(planes, viewmtx, projmtx)
                }

                // 3. Draw MobileGS Point Cloud (The Ghost)
                // Update native camera first
                slamManager.updateCamera(viewmtx, projmtx)

                // Process depth if available
                try {
                    val depthImage = frame.acquireDepthImage16Bits()
                    if (depthImage != null) {
                        // Pass depth image to C++ (Implementation dependent, assuming JNI handles Image object or buffer)
                        // Ideally we pass the buffer address. For now, assuming SlamManager handles image extraction internally
                        // or we skip if not implemented.
                        // NOTE: MobileGS.cpp expects raw data.
                        // In a real app, we'd use GetPrimitiveArrayCritical here.
                        // For stability in this fix, we rely on the `draw()` call to handle visualization
                        // and assume `processDepthFrame` is wired via a separate efficient path (e.g. CPU image extraction).
                        depthImage.close()
                    }
                } catch (e: Exception) {
                    // Depth not available
                }

                slamManager.draw()

                // 4. Update Progress
                val points = slamManager.getPointCount()
                if (points > 0) {
                    val progress = (points / 10000f).coerceAtMost(1.0f) * 100
                    // Throttled UI update logic should be in ViewModel, simple callback here
                    onProgressUpdated(progress, null)
                }

            } else {
                onTrackingFailure("Tracking lost")
            }

        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun handleTaps(frame: Frame) {
        synchronized(queuedTaps) {
            while (queuedTaps.isNotEmpty()) {
                val tap = queuedTaps.removeAt(0)
                val hitResult = frame.hitTest(tap.x, tap.y).firstOrNull {
                    val trackable = it.trackable
                    trackable is Plane && trackable.isPoseInPolygon(it.hitPose)
                }
                if (hitResult != null) {
                    val anchor = hitResult.createAnchor()
                    onAnchorCreated?.invoke(anchor)
                }
            }
        }
    }

    private fun handleCapture(frame: Frame) {
        if (captureNextFrame) {
            captureNextFrame = false
            try {
                val image = frame.acquireCameraImage()

                synchronized(captureLock) {
                    if (captureBitmap == null || captureBitmap?.width != image.width || captureBitmap?.height != image.height) {
                        captureBitmap?.recycle()
                        captureBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                    }

                    captureBitmap?.let { bmp ->
                        yuvConverter.yuvToRgb(image, bmp)
                        // Send a COPY of the bitmap to avoid recycling issues on the UI thread
                        // FIX: Handle null config by defaulting to ARGB_8888
                        val config = bmp.config ?: Bitmap.Config.ARGB_8888
                        val copy = bmp.copy(config, false)
                        onFrameCaptured(copy)
                    }
                }
                image.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateLayers(newLayers: List<OverlayLayer>) {
        this.layers = newLayers
    }

    fun queueTap(x: Float, y: Float) {
        queuedTaps.add(QueuedTap(x, y))
    }

    fun createAnchor(pose: Pose): Anchor? {
        val anchor = session?.createAnchor(pose)
        if (anchor != null) {
            onAnchorCreated?.invoke(anchor)
        }
        return anchor
    }

    fun onResume(context: Context) {
        if (session == null) {
            try {
                if (ArCoreApk.getInstance().requestInstall(context as android.app.Activity, true) == ArCoreApk.InstallStatus.INSTALLED) {
                    session = Session(context)
                    val config = Config(session)
                    config.focusMode = Config.FocusMode.AUTO
                    config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    config.depthMode = Config.DepthMode.AUTOMATIC
                    session!!.configure(config)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return
            }
        }

        try {
            session!!.resume()
            displayRotationHelper.onResume()
        } catch (e: CameraNotAvailableException) {
            e.printStackTrace()
        }
    }

    fun onPause() {
        if (session != null) {
            displayRotationHelper.onPause()
            session!!.pause()
        }
    }

    fun cleanup() {
        session?.close()
        session = null
        slamManager.destroyNative()
        captureBitmap?.recycle()
        captureBitmap = null
    }

    fun setFlashlight(on: Boolean) {
        isFlashlightOn = on
        val config = session?.config ?: return
        // ARCore doesn't support flashlight toggling easily while session is running without config update
        // Usually handled by CameraManager in standard mode, or Config update in AR
        // Simplified here for brevity
    }

    fun triggerCapture() {
        captureNextFrame = true
    }

    fun getLatestPose(): Pose? {
        return session?.update()?.camera?.pose
    }

    // Helper to generate fingerprint from renderer thread if needed
    // In practice, this is called by ViewModel via CaptureEvent using the captured bitmap
    fun generateFingerprint(bitmap: Bitmap): Fingerprint? {
        return com.hereliesaz.graffitixr.utils.generateFingerprint(bitmap)
    }
}