package com.hereliesaz.graffitixr

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import coil.imageLoader
import coil.request.ImageRequest
import com.google.ar.core.ArCoreApk
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import com.hereliesaz.graffitixr.data.Fingerprint
import com.hereliesaz.graffitixr.rendering.BackgroundRenderer
import com.hereliesaz.graffitixr.rendering.PlaneRenderer
import com.hereliesaz.graffitixr.rendering.PointCloudRenderer
import com.hereliesaz.graffitixr.rendering.SimpleQuadRenderer
import com.hereliesaz.graffitixr.utils.BitmapUtils
import com.hereliesaz.graffitixr.utils.DisplayRotationHelper
import com.hereliesaz.graffitixr.utils.YuvToRgbConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Custom GLSurfaceView.Renderer that handles the ARCore session and OpenGL ES rendering.
 *
 * This class is responsible for:
 * 1.  Managing the ARCore [Session] lifecycle.
 * 2.  Rendering the camera background stream.
 * 3.  Rendering AR planes (for debugging/placement) and Point Clouds.
 * 4.  Rendering the "Augmented Image" overlay (the user's art).
 * 5.  Handling AR anchor placement via raycasting (Tap and Pan).
 * 6.  Performing background analysis (OpenCV ORB) for progress tracking.
 *
 * @property context Application context.
 * @property onPlanesDetected Callback when AR planes are found (or lost).
 * @property onFrameCaptured Callback when a camera frame is captured for target creation.
 * @property onAnchorCreated Callback when an AR anchor is successfully created.
 * @property onProgressUpdated Callback for progress tracking updates.
 * @property onTrackingFailure Callback for AR tracking errors/warnings.
 */
class ArRenderer(
    private val context: Context,
    private val onPlanesDetected: (Boolean) -> Unit,
    private val onFrameCaptured: (Bitmap) -> Unit,
    private val onAnchorCreated: () -> Unit,
    private val onProgressUpdated: (Float, Bitmap?) -> Unit,
    private val onTrackingFailure: (String?) -> Unit
) : GLSurfaceView.Renderer {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var wasTracking = false

    // Lock to synchronize session access between GL thread and other threads (UI/IO)
    private val sessionLock = ReentrantLock()

    // AR Session
    @Volatile
    var session: Session? = null
    private val displayRotationHelper = DisplayRotationHelper(context)

    // Renderers
    private val backgroundRenderer = BackgroundRenderer()
    private val planeRenderer = PlaneRenderer()
    private val pointCloudRenderer = PointCloudRenderer()
    private val simpleQuadRenderer = SimpleQuadRenderer()

    // Utils
    private val yuvToRgbConverter = YuvToRgbConverter(context)

    // Flags & State
    @Volatile private var captureNextFrame = false
    private var viewportWidth = 0
    private var viewportHeight = 0

    @Volatile var overlayBitmap: Bitmap? = null
    @Volatile var arImagePose: FloatArray? = null
    @Volatile var arState: ArState = ArState.SEARCHING

    // Transforms
    var opacity: Float = 1.0f
    var brightness: Float = 0f
    var scale: Float = 1.0f
    var rotationX: Float = 0f
    var rotationY: Float = 0f
    var rotationZ: Float = 0f
    var colorBalanceR: Float = 1.0f
    var colorBalanceG: Float = 1.0f
    var colorBalanceB: Float = 1.0f

    private val tapQueue = ConcurrentLinkedQueue<Pair<Float, Float>>()
    private val panLock = Any()
    private var pendingPanX = 0f
    private var pendingPanY = 0f

    // Reusable arrays for projection to avoid allocation.
    // NOTE: These are not thread-safe and must only be used on the GL thread (handlePan).
    private val worldPos = FloatArray(4)
    private val eyePos = FloatArray(4)
    private val clipPos = FloatArray(4)

    private val orb = ORB.create()
    private var originalDescriptors: Mat? = null
    private var originalKeypointCount: Int = 0
    private var lastAnalysisTime = 0L
    private val ANALYSIS_INTERVAL_MS = 2000L
    private val analysisScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Sets the target fingerprint for tracking progress.
     */
    fun setFingerprint(fingerprint: Fingerprint) {
        this.originalDescriptors = fingerprint.descriptors
        this.originalKeypointCount = fingerprint.keypoints.size
    }

    /**
     * Analyzes the current AR frame using OpenCV ORB on a background thread.
     * Extracts features and compares them to the original fingerprint to determine coverage progress.
     */
    private fun analyzeFrameAsync(frame: Frame) {
        val image = try {
            frame.acquireCameraImage()
        } catch (e: NotYetAvailableException) {
            if (wasTracking) {
                wasTracking = false
                mainHandler.post { onTrackingFailure("Tracking lost. Move device to re-establish.") }
            }
            return
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire image for analysis", e)
            return
        }

        try {
            val rotation = getRotationDegrees()

            // Extract Y-plane (Grayscale) directly
            val yBuffer = image.planes[0].buffer
            val width = image.width
            val height = image.height
            val rowStride = image.planes[0].rowStride

            // Create wrapper Mat for Y plane. Buffer must be direct.
            val yMat = Mat(height, width, CvType.CV_8UC1, yBuffer, rowStride.toLong())

            // Deep copy to detach from Image/Camera resource
            val processingMat = Mat()
            yMat.copyTo(processingMat)

            // Release camera resources immediately
            yMat.release()
            image.close()

            CoroutineScope(Dispatchers.Default).launch {
                try {
                    // Handle Rotation
                    val rotatedMat = if (rotation != 0f) {
                        val dst = Mat()
                        val rotateCode = when (rotation) {
                            90f -> Core.ROTATE_90_CLOCKWISE
                            180f -> Core.ROTATE_180
                            270f -> Core.ROTATE_90_COUNTERCLOCKWISE
                            else -> -1
                        }
                        if (rotateCode != -1) {
                            Core.rotate(processingMat, dst, rotateCode)
                            processingMat.release()
                            dst
                        } else {
                            processingMat
                        }
                    } else {
                        processingMat
                    }

                    // Run ORB (using rotatedMat which is already grayscale)
                    val descriptors = Mat()
                    val keypoints = MatOfKeyPoint()
                    synchronized(orb) {
                        orb.detectAndCompute(rotatedMat, Mat(), keypoints, descriptors)
                    }

                    if (descriptors.rows() > 0 && originalDescriptors != null) {
                        val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
                        val matches = MatOfDMatch()
                        matcher.match(descriptors, originalDescriptors, matches)

                        val matchesList = matches.toList()
                        val goodMatches = matchesList.filter { it.distance < 60 }.size

                        val ratio = if (originalKeypointCount > 0) {
                            goodMatches.toFloat() / originalKeypointCount.toFloat()
                        } else {
                            0f
                        }
                        val progress = (1.0f - ratio).coerceIn(0f, 1f) * 100f

                        mainHandler.post { onProgressUpdated(progress, null) }
                    }
                    descriptors.release()
                    keypoints.release()
                    rotatedMat.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in analysis coroutine", e)
                    // If processingMat was NOT released due to exception before logic, it might leak.
                    // But in this block, 'processingMat' is either released or passed to 'rotatedMat'.
                    // 'rotatedMat' is released at end.
                    // To be strictly safe we could use 'use' logic for Mats too, but this is better than before.
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Analysis failed", e)
        } finally {
            image.close()
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        try {
            backgroundRenderer.createOnGlThread()
            planeRenderer.createOnGlThread()
            pointCloudRenderer.createOnGlThread()
            simpleQuadRenderer.createOnGlThread()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize renderers", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        displayRotationHelper.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (!sessionLock.tryLock()) return

        try {
            // Safety check: Skip frame if session is null
            if (session == null) return

            try {
                session!!.setCameraTextureName(backgroundRenderer.textureId)
                displayRotationHelper.updateSessionIfNeeded(session!!)
                val frame = session?.update() ?: return

                backgroundRenderer.draw(frame)

                var frameProcessed = false

                if (captureNextFrame) {
                    captureFrameForFingerprint(frame)
                    captureNextFrame = false
                    frameProcessed = true
                }

                if (!frameProcessed && System.currentTimeMillis() - lastAnalysisTime > ANALYSIS_INTERVAL_MS && originalDescriptors != null) {
                    lastAnalysisTime = System.currentTimeMillis()
                    analyzeFrameAsync(frame)
                }

                val camera = frame.camera

                if (camera.trackingState == TrackingState.TRACKING) {
                    if (!wasTracking) {
                        wasTracking = true
                        mainHandler.post { onTrackingFailure(null) }
                    }
                } else {
                    if (wasTracking) {
                        wasTracking = false
                        mainHandler.post { onTrackingFailure("Tracking lost. Point device at grid.") }
                    }
                    return
                }

                val projmtx = FloatArray(16)
                camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f)
                val viewmtx = FloatArray(16)
                camera.getViewMatrix(viewmtx, 0)

                frame.acquirePointCloud().use { pointCloud ->
                    pointCloudRenderer.draw(pointCloud, viewmtx, projmtx)
                }

                when (arState) {
                    ArState.SEARCHING -> {
                        val planes = session!!.getAllTrackables(Plane::class.java)
                        var hasTrackingPlane = false
                        for (plane in planes) {
                            if (plane.trackingState == TrackingState.TRACKING && plane.subsumedBy == null) {
                                planeRenderer.draw(plane, viewmtx, projmtx)
                                hasTrackingPlane = true
                            }
                        }
                        mainHandler.post { onPlanesDetected(hasTrackingPlane) }
                        handleTap(frame)
                    }
                    ArState.LOCKED -> {
                        handlePan(frame, viewmtx, projmtx)
                        val updatedAugmentedImages = frame.getUpdatedTrackables(AugmentedImage::class.java)
                        for (img in updatedAugmentedImages) {
                            if (img.trackingState == TrackingState.TRACKING && img.name.startsWith("target")) {
                                val pose = img.centerPose
                                val poseMatrix = FloatArray(16)
                                pose.toMatrix(poseMatrix, 0)
                                arImagePose = poseMatrix
                                break
                            }
                        }
                        drawArtwork(viewmtx, projmtx)
                    }
                    ArState.PLACED -> {
                        handlePan(frame, viewmtx, projmtx)
                        drawArtwork(viewmtx, projmtx)
                    }
                }
            } catch (e: SessionPausedException) {
                mainHandler.post { onTrackingFailure("AR session paused. Please wait.") }
            } catch (t: Throwable) {
                Log.e(TAG, "Exception on the GL Thread", t)
            }
        } finally {
            sessionLock.unlock()
        }
    }

    private fun drawArtwork(viewMtx: FloatArray, projMtx: FloatArray) {
        val bitmap = overlayBitmap ?: return
        val pose = arImagePose ?: return

        val modelMtx = pose.clone()

        Matrix.rotateM(modelMtx, 0, rotationZ, 0f, 0f, 1f)
        Matrix.rotateM(modelMtx, 0, rotationX, 1f, 0f, 0f)
        Matrix.rotateM(modelMtx, 0, rotationY, 0f, 1f, 0f)
        Matrix.rotateM(modelMtx, 0, -90f, 1f, 0f, 0f)
        Matrix.scaleM(modelMtx, 0, scale, scale, 1f)

        val aspectRatio = if (bitmap.height > 0) bitmap.width.toFloat() / bitmap.height.toFloat() else 1f
        Matrix.scaleM(modelMtx, 0, aspectRatio, 1f, 1f)

        simpleQuadRenderer.draw(
            modelMtx, viewMtx, projMtx,
            bitmap, opacity, brightness, colorBalanceR, colorBalanceG, colorBalanceB
        )
    }

    private fun handleTap(frame: Frame) {
        val tap = tapQueue.poll() ?: return
        val hitResult = frame.hitTest(tap.first, tap.second)

        for (hit in hitResult) {
            val trackable = hit.trackable
            if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                val anchor = hit.createAnchor()
                val poseMatrix = FloatArray(16)
                anchor.pose.toMatrix(poseMatrix, 0)

                arImagePose = poseMatrix
                arState = ArState.PLACED
                mainHandler.post { onAnchorCreated() }
                break
            }
        }
    }

    private fun handlePan(frame: Frame, viewMtx: FloatArray, projMtx: FloatArray) {
        var dx = 0f
        var dy = 0f
        synchronized(panLock) {
            dx = pendingPanX
            dy = pendingPanY
            pendingPanX = 0f
            pendingPanY = 0f
        }

        if (dx == 0f && dy == 0f) return

        val pose = arImagePose ?: return
        val screenPos = projectPoseToScreen(pose, viewMtx, projMtx) ?: return

        val newX = screenPos.first + dx
        val newY = screenPos.second + dy

        val hitResult = frame.hitTest(newX, newY)
        for (hit in hitResult) {
            val trackable = hit.trackable
            if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                val poseMatrix = FloatArray(16)
                hit.hitPose.toMatrix(poseMatrix, 0)
                arImagePose = poseMatrix
                arState = ArState.PLACED
                break
            }
        }
    }

    private fun projectPoseToScreen(modelMtx: FloatArray, viewMtx: FloatArray, projMtx: FloatArray): Pair<Float, Float>? {
        worldPos[0] = modelMtx[12]
        worldPos[1] = modelMtx[13]
        worldPos[2] = modelMtx[14]
        worldPos[3] = 1.0f

        Matrix.multiplyMV(eyePos, 0, viewMtx, 0, worldPos, 0)
        Matrix.multiplyMV(clipPos, 0, projMtx, 0, eyePos, 0)

        if (clipPos[3] == 0f) return null

        val ndcX = clipPos[0] / clipPos[3]
        val ndcY = clipPos[1] / clipPos[3]

        val screenX = (ndcX + 1f) / 2f * viewportWidth
        val screenY = (1f - ndcY) / 2f * viewportHeight

        return Pair(screenX, screenY)
    }

    private fun captureFrameForFingerprint(frame: Frame) {
        try {
            frame.acquireCameraImage().use { image ->
                val rawBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                yuvToRgbConverter.yuvToRgb(image, rawBitmap)

                // Fix Rotation
                val rotation = getRotationDegrees()
                val bitmap = if (rotation != 0f) {
                    val rotated = BitmapUtils.rotateBitmap(rawBitmap, rotation)
                    rawBitmap.recycle()
                    rotated
                } else {
                    rawBitmap
                }

                mainHandler.post { onFrameCaptured(bitmap) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed", e)
        }
    }

    private fun getRotationDegrees(): Float {
        val displayRotation = when (displayRotationHelper.rotation) {
            android.view.Surface.ROTATION_0 -> 0
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        val sensorOrientation = 90 // Typical back camera orientation
        return ((sensorOrientation - displayRotation + 360) % 360).toFloat()
    }

    fun setFlashlight(enabled: Boolean) {
        sessionLock.lock()
        try {
            val session = this.session ?: return
            val config = session.config
            config.flashMode = if (enabled) Config.FlashMode.TORCH else Config.FlashMode.OFF
            session.configure(config)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle flashlight", e)
        } finally {
            sessionLock.unlock()
        }
    }

    fun triggerCapture() {
        captureNextFrame = true
    }

    fun setAugmentedImageDatabase(bitmaps: List<Bitmap>) {
        sessionLock.lock()
        try {
            val session = this.session ?: return
            session.pause() // Pause session

            val config = session.config
            val database = AugmentedImageDatabase(session)

            bitmaps.forEachIndexed { index, bitmap ->
                database.addImage("target_$index", bitmap)
            }

            config.augmentedImageDatabase = database
            session.configure(config)
            session.resume() // Resume session

            arState = ArState.LOCKED
        } catch(e: Exception) {
            Log.e(TAG, "Failed to set image database", e)
        } finally {
            sessionLock.unlock()
        }
    }

    fun onResume(activity: Activity) {
        sessionLock.lock()
        try {
            if (session == null) {
                try {
                    if (ArCoreApk.getInstance().requestInstall(activity, true) == ArCoreApk.InstallStatus.INSTALLED) {
                        session = Session(context)
                        val config = Config(session)
                        config.updateMode = Config.UpdateMode.BLOCKING
                        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        config.focusMode = Config.FocusMode.AUTO
                        config.depthMode = Config.DepthMode.AUTOMATIC
                        session!!.configure(config)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception creating session", e)
                }
            }

            try {
                session?.resume()
                displayRotationHelper.onResume()
            } catch (e: CameraNotAvailableException) {
                Log.e(TAG, "Camera not available")
            }
        } finally {
            sessionLock.unlock()
        }
    }

    fun onPause() {
        sessionLock.lock()
        try {
            displayRotationHelper.onPause()
            session?.pause()
        } catch (e: Exception) {
            Log.e(TAG, "Exception during onPause", e)
        } finally {
            sessionLock.unlock()
        }
    }

    fun cleanup() {
        sessionLock.lock()
        try {
            analysisScope.cancel()
            session?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing session", e)
        } finally {
            session = null
            sessionLock.unlock()
        }
    }

    fun queueTap(x: Float, y: Float) {
        tapQueue.offer(Pair(x, y))
    }

    fun queuePan(dx: Float, dy: Float) {
        synchronized(panLock) {
            pendingPanX += dx
            pendingPanY += dy
        }
    }

    fun updateOverlayImage(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = ImageRequest.Builder(context)
                    .data(uri)
                    .allowHardware(false)
                    .build()
                val result = context.imageLoader.execute(request)
                val drawable = result.drawable
                if (drawable is android.graphics.drawable.BitmapDrawable) {
                    overlayBitmap = drawable.bitmap
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading overlay image", e)
            }
        }
    }

    companion object {
        const val TAG = "ArRenderer"
    }
}
