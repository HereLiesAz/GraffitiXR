package com.hereliesaz.graffitixr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.widget.Toast
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
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
import com.hereliesaz.graffitixr.utils.ImageUtils
import com.hereliesaz.graffitixr.utils.Texture
import java.io.IOException
import java.nio.ByteBuffer
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
    
    val slamManager = SlamManager()

    private var viewWidth = 0
    private var viewHeight = 0

    private var isFlashlightOn = false
    private var captureNextFrame = false
    
    // Layers to render
    private var layers: List<OverlayLayer> = emptyList()
    
    // Tap handling
    private val queuedTaps = Collections.synchronizedList(ArrayList<QueuedTap>())
    var onAnchorCreated: ((Anchor) -> Unit)? = null

    data class QueuedTap(val x: Float, val y: Float)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        try {
            backgroundRenderer.createOnGlThread(context)
            planeRenderer.createOnGlThread(context, "models/trigrid.png")
            imageRenderer.createOnGlThread(context)
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

            handleTaps(frame, camera)
            handleCapture(frame)

            backgroundRenderer.draw(frame)

            val projmtx = FloatArray(16)
            camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f)
            val viewmtx = FloatArray(16)
            camera.getViewMatrix(viewmtx, 0)

            val cameraPose = camera.pose
            val trackingState = camera.trackingState

            if (trackingState == TrackingState.TRACKING) {
                onTrackingFailure(null)
                
                // Plane Detection Logic
                val hasPlanes = session!!.getAllTrackables(Plane::class.java).any { it.trackingState == TrackingState.TRACKING }
                onPlanesDetected(hasPlanes)
                
                if (hasPlanes) {
                    planeRenderer.drawPlanes(session!!.getAllTrackables(Plane::class.java), camera.displayOrientedPose, projmtx)
                }

                // Render Layers
                layers.forEach { layer ->
                    // Logic to render layer anchors...
                    // Simplified for brevity, assumes layer has an anchor logic or similar
                }
                
                // SLAM Process
                // Pass camera image to SLAM
                // Note: Acquiring image can be expensive, do it only if needed
                // val image = frame.acquireCameraImage()
                // slamManager.processFrame(...)
                // image.close()
                
                // Update Native Camera
                slamManager.updateCamera(viewmtx, projmtx)
                slamManager.draw()
                
                // Update Progress (Example logic)
                val points = slamManager.getPointCount()
                if (points > 0) {
                    val progress = (points / 10000f).coerceAtMost(1.0f) * 100
                    onProgressUpdated(progress, null)
                }

            } else {
                onTrackingFailure("Tracking lost")
            }

        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun handleTaps(frame: Frame, camera: com.google.ar.core.Camera) {
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
                val bitmap = ImageUtils.yuvToRgb(image, context)
                image.close()
                onFrameCaptured(bitmap)
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

    fun onResume(context: Context) {
        if (session == null) {
            try {
                if (ArCoreApk.getInstance().requestInstall(context as android.app.Activity, true) == ArCoreApk.InstallStatus.INSTALLED) {
                    session = Session(context)
                    val config = Config(session)
                    config.focusMode = Config.FocusMode.AUTO
                    config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    session!!.configure(config)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "ARCore Failed", Toast.LENGTH_LONG).show()
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
    }

    fun setFlashlight(on: Boolean) {
        isFlashlightOn = on
        val config = session?.config ?: return
        if (on) {
            // config.flashMode = Config.FlashMode.TORCH // Requires ARCore dependency update if not available
        } else {
            // config.flashMode = Config.FlashMode.OFF
        }
        // session?.configure(config)
    }

    fun triggerCapture() {
        captureNextFrame = true
    }
    
    fun getLatestPose(): Pose? {
        return session?.update()?.camera?.pose
    }
    
    fun generateFingerprint(bitmap: Bitmap): Fingerprint? {
        // Placeholder for fingerprint logic
        return null
    }
}
