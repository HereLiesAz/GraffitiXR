package com.hereliesaz.graffitixr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import android.widget.Toast
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
    
    val slamManager = SlamManager()

    var onSessionUpdated: ((Session, Frame) -> Unit)? = null
    var onAnchorCreated: ((Anchor) -> Unit)? = null

    var showMiniMap = false
    var showGuide = false

    private var isInitialized = false
    private var isResumed = false
    private var captureNextFrame = false
    
    private var layers: List<OverlayLayer> = emptyList()
    private val queuedTaps = Collections.synchronizedList(ArrayList<QueuedTap>())

    data class QueuedTap(val x: Float, val y: Float)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        try {
            backgroundRenderer.createOnGlThread()
            planeRenderer.createOnGlThread() 
            imageRenderer.createOnGlThread()
            slamManager.initNative()
            isInitialized = true
        } catch (e: IOException) {
            Log.e("ArRenderer", "Failed to initialize renderer", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
        GLES30.glViewport(0, 0, width, height)
        slamManager.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        if (!isInitialized) return
        val currentSession = session ?: return

        if (!isResumed) {
            try {
                currentSession.resume()
                isResumed = true
            } catch (e: Exception) {
                Log.e("ArRenderer", "Failed to resume session", e)
                return
            }
        }

        displayRotationHelper.updateSessionIfNeeded(currentSession)

        try {
            currentSession.setCameraTextureName(backgroundRenderer.textureId)
            val frame = currentSession.update()
            val camera = frame.camera

            onSessionUpdated?.invoke(currentSession, frame)

            handleTaps(frame)
            handleCapture(frame)

            backgroundRenderer.draw(frame)

            if (camera.trackingState == TrackingState.TRACKING) {
                onTrackingFailure(null)
                
                val projmtx = FloatArray(16)
                camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f)
                val viewmtx = FloatArray(16)
                camera.getViewMatrix(viewmtx, 0)

                val hasPlanes = currentSession.getAllTrackables(Plane::class.java).any { it.trackingState == TrackingState.TRACKING }
                onPlanesDetected(hasPlanes)
                
                if (hasPlanes) {
                    planeRenderer.drawPlanes(
                        currentSession.getAllTrackables(Plane::class.java), 
                        viewmtx, 
                        projmtx
                    )
                }
                
                slamManager.updateCamera(viewmtx, projmtx)
                slamManager.draw()
                
                val points = slamManager.getPointCount()
                if (points > 0) {
                    val progress = (points / 10000f).coerceAtMost(1.0f) * 100
                    onProgressUpdated(progress, null)
                }
            } else {
                onTrackingFailure("Tracking lost: ${camera.trackingFailureReason}")
            }

        } catch (t: Throwable) {
            Log.e("ArRenderer", "Exception on the GL thread", t)
        }
    }

    private fun handleTaps(frame: Frame) {
        synchronized(queuedTaps) {
            while (queuedTaps.isNotEmpty()) {
                val tap = queuedTaps.removeAt(0)
                val hitResults = frame.hitTest(tap.x, tap.y)
                for (hit in hitResults) {
                    val trackable = hit.trackable
                    if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                        val anchor = hit.createAnchor()
                        onAnchorCreated?.invoke(anchor)
                        break
                    }
                }
            }
        }
    }
    
    private fun handleCapture(frame: Frame) {
        if (captureNextFrame) {
            captureNextFrame = false
            try {
                frame.acquireCameraImage().use { image ->
                    if (captureBitmap == null || captureBitmap?.width != image.width || captureBitmap?.height != image.height) {
                        captureBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                    }
                    captureBitmap?.let { bmp ->
                        yuvConverter.yuvToRgb(image, bmp)
                        onFrameCaptured(bmp)
                    }
                }
            } catch (e: Exception) {
                Log.e("ArRenderer", "Failed to capture frame", e)
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
                val installStatus = ArCoreApk.getInstance().requestInstall(context as android.app.Activity, true)
                if (installStatus == ArCoreApk.InstallStatus.INSTALLED) {
                    session = Session(context)
                    val config = Config(session)
                    config.focusMode = Config.FocusMode.AUTO
                    config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    if (session!!.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        config.depthMode = Config.DepthMode.AUTOMATIC
                    }
                    session!!.configure(config)
                }
            } catch (e: Exception) {
                Log.e("ArRenderer", "ARCore Session creation failed", e)
            }
        }
    }

    fun onPause() {
        isResumed = false
        displayRotationHelper.onPause()
        session?.pause()
    }

    fun cleanup() {
        isInitialized = false
        session?.close()
        session = null
        slamManager.destroyNative()
    }

    fun setFlashlight(on: Boolean) {
        showMiniMap = on // Mock use to prevent unused warning if necessary, though logic should be added
    }

    fun triggerCapture() {
        captureNextFrame = true
    }
    
    fun getLatestPose(): Pose? {
        return try { session?.update()?.camera?.pose } catch (e: Exception) { null }
    }
    
    fun generateFingerprint(bitmap: Bitmap): Fingerprint? {
        return null
    }
}
