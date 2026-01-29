package com.hereliesaz.graffitixr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.media.Image
import android.util.Log
import com.google.ar.core.*
import com.hereliesaz.graffitixr.data.Fingerprint
import com.hereliesaz.graffitixr.rendering.*
import com.hereliesaz.graffitixr.slam.SlamManager
import com.hereliesaz.graffitixr.utils.DisplayRotationHelper
import com.hereliesaz.graffitixr.utils.ImageProcessingUtils
import com.hereliesaz.graffitixr.utils.YuvToRgbConverter
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
    val gridRenderer = GridRenderer()

    val slamManager = SlamManager()
    private val yuvConverter = YuvToRgbConverter(context)

    var onSessionUpdated: ((Session, Frame) -> Unit)? = null
    var onAnchorCreated: ((Anchor) -> Unit)? = null

    private var captureBitmap: Bitmap? = null
    @Volatile private var captureNextFrame = false
    @Volatile private var captureFingerprint = false
    var onFingerprintReady: ((Fingerprint) -> Unit)? = null

    private var lastDepthTime = 0L
    private val DEPTH_INTERVAL_MS = 100L

    private var isSessionResumed = false
    private var isSurfaceCreated = false
    private var viewWidth = 0
    private var viewHeight = 0
    private var isFlashlightOn = false

    // NEW: Active Anchor state
    private var currentAnchor: Anchor? = null

    private val queuedTaps = Collections.synchronizedList(ArrayList<QueuedTap>())
    data class QueuedTap(val x: Float, val y: Float)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        try {
            backgroundRenderer.createOnGlThread()
            planeRenderer.createOnGlThread()
            gridRenderer.createOnGlThread()
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

            backgroundRenderer.draw(frame)

            onSessionUpdated?.invoke(session!!, frame)
            handleTaps(frame)

            if (captureNextFrame) {
                captureNextFrame = false
                try {
                    val image = frame.acquireCameraImage()
                    val bmp = convertImageToBitmap(image)
                    image.close()
                    bmp?.let { onFrameCaptured(it) }
                } catch (e: Exception) { e.printStackTrace() }
            }

            if (captureFingerprint) {
                captureFingerprint = false
                try {
                    val image = frame.acquireCameraImage()
                    val depthImage = frame.acquireDepthImage16Bits()

                    if (depthImage != null) {
                        val bmp = convertImageToBitmap(image)
                        if (bmp != null) {
                            val intrinsics = camera.imageIntrinsics
                            val fParams = floatArrayOf(
                                intrinsics.focalLength[0], intrinsics.focalLength[1],
                                intrinsics.principalPoint[0], intrinsics.principalPoint[1]
                            )

                            val depthPlane = depthImage.planes[0]
                            val depthData = ByteBuffer.allocateDirect(depthPlane.buffer.limit())
                            depthPlane.buffer.rewind()
                            depthData.put(depthPlane.buffer)

                            val fp = ImageProcessingUtils.generateFingerprintWithDepth(
                                bmp, depthData, depthImage.width, depthImage.height, fParams
                            )
                            onFingerprintReady?.invoke(fp)
                        }
                        depthImage.close()
                    }
                    image.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val projmtx = FloatArray(16)
            camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f)
            val viewmtx = FloatArray(16)
            camera.getViewMatrix(viewmtx, 0)

            if (camera.trackingState == TrackingState.TRACKING) {
                onTrackingFailure(null)

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
                    } catch (e: Exception) { }
                }

                slamManager.updateCamera(viewmtx, projmtx)
                slamManager.draw()

                val planes = session!!.getAllTrackables(Plane::class.java)
                val hasPlanes = planes.any { it.trackingState == TrackingState.TRACKING }
                onPlanesDetected(hasPlanes)
                if (hasPlanes) {
                    planeRenderer.drawPlanes(planes, viewmtx, projmtx)
                }

                // NEW: Draw Grid if Anchor is present and tracking
                if (currentAnchor != null && currentAnchor!!.trackingState == TrackingState.TRACKING) {
                    val anchorMtx = FloatArray(16)
                    currentAnchor!!.pose.toMatrix(anchorMtx, 0)
                    gridRenderer.draw(viewmtx, projmtx, anchorMtx)
                }

            } else {
                onTrackingFailure("Tracking lost")
            }

        } catch (t: Throwable) { }
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
                    // NEW: Update local anchor reference
                    currentAnchor?.detach()
                    currentAnchor = anchor
                    onAnchorCreated?.invoke(anchor)
                }
            }
        }
    }

    private fun convertImageToBitmap(image: Image): Bitmap? {
        synchronized(this) {
            if (captureBitmap == null || captureBitmap?.width != image.width || captureBitmap?.height != image.height) {
                captureBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            }
            yuvConverter.yuvToRgb(image, captureBitmap!!)
            return captureBitmap?.copy(captureBitmap!!.config, false)
        }
    }

    fun triggerCapture() { captureNextFrame = true }
    fun triggerFingerprintCapture() { captureFingerprint = true }

    fun updateLayers(newLayers: List<com.hereliesaz.graffitixr.data.OverlayLayer>) { }
    fun setFlashlight(on: Boolean) { isFlashlightOn = on }
    fun queueTap(x: Float, y: Float) { queuedTaps.add(QueuedTap(x, y)) }

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
            } catch (e: Exception) { return }
        }
        try {
            session!!.resume()
            isSessionResumed = true
            displayRotationHelper.onResume()
            if (isSurfaceCreated && backgroundRenderer.textureId != -1) {
                session!!.setCameraTextureName(backgroundRenderer.textureId)
            }
            slamManager.initNative()
        } catch (e: Exception) { e.printStackTrace() }
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

    fun getLatestPose(): Pose? { return session?.update()?.camera?.pose }
    fun generateFingerprint(bitmap: Bitmap): Fingerprint? { return com.hereliesaz.graffitixr.utils.generateFingerprint(bitmap) }

    fun createAnchor(pose: Pose): Anchor? {
        val anchor = session?.createAnchor(pose)
        if (anchor != null) {
            currentAnchor?.detach()
            currentAnchor = anchor
            onAnchorCreated?.invoke(anchor)
        }
        return anchor
    }
}