package com.hereliesaz.graffitixr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
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

    private val backgroundRenderer = BackgroundRenderer()
    private val planeRenderer = PlaneRenderer()
    private val imageRenderer = ProjectedImageRenderer()

    private val yuvConverter = YuvToRgbConverter(context)
    private var captureBitmap: Bitmap? = null
    private val captureLock = Any()

    val slamManager = SlamManager()

    var onSessionUpdated: ((Session, Frame) -> Unit)? = null
    var onAnchorCreated: ((Anchor) -> Unit)? = null

    var showMiniMap = false
    var showGuide = false

    private var viewWidth = 0
    private var viewHeight = 0
    private var isFlashlightOn = false
    @Volatile private var captureNextFrame = false
    private var isSessionResumed = false
    private var isSurfaceCreated = false

    // THROTTLE: Limit depth processing to ~10 FPS to prevent CPU starvation
    private var lastDepthTime = 0L
    private val DEPTH_INTERVAL_MS = 100L

    private var layers: List<OverlayLayer> = emptyList()
    private val queuedTaps = Collections.synchronizedList(ArrayList<QueuedTap>())

    data class QueuedTap(val x: Float, val y: Float)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        try {
            backgroundRenderer.createOnGlThread()
            planeRenderer.createOnGlThread()
            imageRenderer.createOnGlThread()

            // Re-enable Native
            slamManager.initNative()
            isSurfaceCreated = true

            if (session != null) {
                session!!.setCameraTextureName(backgroundRenderer.textureId)
            }
        } catch (e: IOException) {
            Log.e("ArRenderer", "Failed to init GL", e)
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

        if (session == null || !isSessionResumed || !isSurfaceCreated) return

        displayRotationHelper.updateSessionIfNeeded(session!!)

        try {
            if (backgroundRenderer.textureId != -1) {
                session!!.setCameraTextureName(backgroundRenderer.textureId)
            }

            val frame = session!!.update()
            val camera = frame.camera

            // 1. Draw Background
            backgroundRenderer.draw(frame)

            onSessionUpdated?.invoke(session!!, frame)
            handleTaps(frame)
            handleCapture(frame)

            val projmtx = FloatArray(16)
            camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f)
            val viewmtx = FloatArray(16)
            camera.getViewMatrix(viewmtx, 0)

            if (camera.trackingState == TrackingState.TRACKING) {
                onTrackingFailure(null)

                // 2. Feed Depth to Native Engine (Throttled)
                val now = System.currentTimeMillis()
                if (now - lastDepthTime > DEPTH_INTERVAL_MS) {
                    try {
                        val depthImage = frame.acquireDepthImage16Bits()
                        if (depthImage != null) {
                            val buffer = depthImage.planes[0].buffer
                            slamManager.feedDepthData(buffer, depthImage.width, depthImage.height)
                            depthImage.close()
                            lastDepthTime = now
                        }
                    } catch (e: Exception) {
                        // Depth not available yet, ignore
                    }
                }

                // 3. Update & Draw SLAM Map
                slamManager.updateCamera(viewmtx, projmtx)
                slamManager.draw()

                // 4. Draw Planes (Visual verification of AR)
                val planes = session!!.getAllTrackables(Plane::class.java)
                val hasPlanes = planes.any { it.trackingState == TrackingState.TRACKING }
                onPlanesDetected(hasPlanes)
                if (hasPlanes) {
                    planeRenderer.drawPlanes(planes, viewmtx, projmtx)
                }

                // 5. Progress Update
                val points = slamManager.getPointCount()
                if (points > 0) {
                    val progress = (points / 10000f).coerceAtMost(1.0f) * 100
                    onProgressUpdated(progress, null)
                }

            } else {
                onTrackingFailure("Tracking lost")
            }

        } catch (t: Throwable) {
            // Log.e("ArRenderer", "Draw error", t)
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
        if (anchor != null) onAnchorCreated?.invoke(anchor)
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

                    // RE-ENABLE DEPTH
                    config.depthMode = Config.DepthMode.AUTOMATIC

                    session!!.configure(config)
                }
            } catch (e: Exception) {
                return
            }
        }

        try {
            session!!.resume()
            isSessionResumed = true
            displayRotationHelper.onResume()
            if (isSurfaceCreated && backgroundRenderer.textureId != -1) {
                session!!.setCameraTextureName(backgroundRenderer.textureId)
            }
            // Ensure native is ready
            slamManager.initNative()
        } catch (e: CameraNotAvailableException) {
            e.printStackTrace()
        }
    }

    fun onPause() {
        isSessionResumed = false
        if (session != null) {
            displayRotationHelper.onPause()
            session!!.pause()
        }
        slamManager.destroyNative()
    }

    fun cleanup() {
        isSessionResumed = false
        isSurfaceCreated = false
        session?.close()
        session = null
        slamManager.destroyNative()
        captureBitmap?.recycle()
        captureBitmap = null
    }

    fun setFlashlight(on: Boolean) {
        isFlashlightOn = on
    }

    fun triggerCapture() {
        captureNextFrame = true
    }

    fun getLatestPose(): Pose? {
        return session?.update()?.camera?.pose
    }

    fun generateFingerprint(bitmap: Bitmap): Fingerprint? {
        return com.hereliesaz.graffitixr.utils.generateFingerprint(bitmap)
    }
}