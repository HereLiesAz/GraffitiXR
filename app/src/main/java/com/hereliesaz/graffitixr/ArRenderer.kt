package com.hereliesaz.graffitixr

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import coil.imageLoader
import coil.request.ImageRequest
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
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
import kotlin.math.abs
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
import java.util.concurrent.locks.ReentrantLock
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Custom GLSurfaceView.Renderer that handles the ARCore session and OpenGL ES rendering.
 *
 * Updates:
 * - Calculates screen-space bounding box of the artwork and sends to ViewModel.
 */
class ArRenderer(
    private val context: Context,
    private val onPlanesDetected: (Boolean) -> Unit,
    private val onFrameCaptured: (Bitmap) -> Unit,
    private val onAnchorCreated: () -> Unit,
    private val onProgressUpdated: (Float, Bitmap?) -> Unit,
    private val onTrackingFailure: (String?) -> Unit,
    private val onBoundsUpdated: (RectF) -> Unit // New callback for bounds
) : GLSurfaceView.Renderer {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var wasTracking = false
    private val sessionLock = ReentrantLock()

    @Volatile var session: Session? = null
    private val displayRotationHelper = DisplayRotationHelper(context)

    // Renderers
    private val backgroundRenderer = BackgroundRenderer()
    private val planeRenderer = PlaneRenderer()
    private val pointCloudRenderer = PointCloudRenderer()
    private val simpleQuadRenderer = SimpleQuadRenderer()
    private val yuvToRgbConverter = YuvToRgbConverter(context)

    // Depth
    private var isDepthSupported = false
    private var depthTextureId = -1

    // State
    @Volatile private var captureNextFrame = false
    private var viewportWidth = 0
    private var viewportHeight = 0
    @Volatile var overlayBitmap: Bitmap? = null
    @Volatile var arImagePose: FloatArray? = null
    @Volatile var arState: ArState = ArState.SEARCHING
    private var activeAnchor: Anchor? = null

    // Configuration for next init (Lazy Loading)
    private var initialAugmentedImages: List<Bitmap>? = null

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

    // Reusable matrices for calculations
    private val worldPos = FloatArray(4)
    private val eyePos = FloatArray(4)
    private val clipPos = FloatArray(4)
    private val viewMtx = FloatArray(16)
    private val projMtx = FloatArray(16)

    // Bolt Optimization: Pre-allocated buffers to avoid allocations in onDrawFrame
    private val calculationModelMatrix = FloatArray(16)
    private val calculationPoseMatrix = FloatArray(16)
    private val calculationTempVec = FloatArray(4)
    private val calculationResVec = FloatArray(4)

    // Bolt Optimization: Reusable RectF and state for bounds optimization to reduce allocations and UI updates
    private val calculationBounds = RectF()
    private val lastReportedBounds = RectF()
    private var hasReportedBounds = false
    private val BOUNDS_UPDATE_THRESHOLD = 2f // pixels

    private val boundsCorners = floatArrayOf(
        -0.5f, -0.5f, 0f, 1f,
        -0.5f, 0.5f, 0f, 1f,
        0.5f, 0.5f, 0f, 1f,
        0.5f, -0.5f, 0f, 1f
    )

    private val orb = ORB.create()
    private var originalDescriptors: Mat? = null
    private var originalKeypointCount: Int = 0
    private var lastAnalysisTime = 0L
    private val ANALYSIS_INTERVAL_MS = 1500L
    private val analysisScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun setFingerprint(fingerprint: Fingerprint) {
        this.originalDescriptors = fingerprint.descriptors
        this.originalKeypointCount = fingerprint.keypoints.size
    }

    fun setAugmentedImageDatabase(bitmaps: List<Bitmap>) {
        this.initialAugmentedImages = bitmaps
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        try {
            backgroundRenderer.createOnGlThread()
            planeRenderer.createOnGlThread()
            pointCloudRenderer.createOnGlThread()
            simpleQuadRenderer.createOnGlThread()
            createDepthTexture()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize renderers", e)
        }
    }

    private fun createDepthTexture() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        depthTextureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
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
            if (session == null) return

            if (isDepthSupported) {
                try {
                    val frame = session!!.update()
                    updateDepthTexture(frame)
                    drawFrame(frame)
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating frame", e)
                }
            } else {
                val frame = session?.update() ?: return
                drawFrame(frame)
            }
        } catch (e: SessionPausedException) {
            // Paused
        } catch (t: Throwable) {
            Log.e(TAG, "Exception on GL Thread", t)
        } finally {
            sessionLock.unlock()
        }
    }

    private fun updateDepthTexture(frame: Frame) {
        try {
            val depthImage = frame.acquireDepthImage16Bits()
            if (depthImage != null) {
                val buffer = depthImage.planes[0].buffer
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextureId)
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
                    depthImage.width, depthImage.height, 0,
                    GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_SHORT, buffer
                )
                depthImage.close()
            }
        } catch (e: NotYetAvailableException) {
            // Ignore
        }
    }

    private fun drawFrame(frame: Frame) {
        session!!.setCameraTextureName(backgroundRenderer.textureId)
        displayRotationHelper.updateSessionIfNeeded(session!!)
        backgroundRenderer.draw(frame)

        if (captureNextFrame) {
            captureFrameForFingerprint(frame)
            captureNextFrame = false
        }

        if (System.currentTimeMillis() - lastAnalysisTime > ANALYSIS_INTERVAL_MS && originalDescriptors != null) {
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
                mainHandler.post { onTrackingFailure("Tracking lost.") }
            }
            return
        }

        camera.getProjectionMatrix(projMtx, 0, 0.1f, 100.0f)
        camera.getViewMatrix(viewMtx, 0)

        // --- Anchor Logic ---
        if (activeAnchor == null && arState != ArState.PLACED) {
            val updatedAugmentedImages = frame.getUpdatedTrackables(AugmentedImage::class.java)
            for (img in updatedAugmentedImages) {
                if (img.trackingState == TrackingState.TRACKING && img.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING) {
                    activeAnchor = img.createAnchor(img.centerPose)
                    arState = ArState.LOCKED
                    mainHandler.post { onAnchorCreated() }
                    break
                }
            }
        }

        if (activeAnchor != null) {
            if (activeAnchor!!.trackingState == TrackingState.TRACKING) {
                val pose = activeAnchor!!.pose
                // Bolt Optimization: Reuse matrix
                pose.toMatrix(calculationPoseMatrix, 0)
                arImagePose = calculationPoseMatrix
                handlePan(frame, viewMtx, projMtx)
            }
        }

        if (activeAnchor == null) {
            val planes = session!!.getAllTrackables(Plane::class.java)
            var hasPlane = false
            for (plane in planes) {
                if (plane.trackingState == TrackingState.TRACKING && plane.subsumedBy == null) {
                    planeRenderer.draw(plane, viewMtx, projMtx)
                    hasPlane = true
                }
            }
            mainHandler.post { onPlanesDetected(hasPlane) }
            handleTap(frame)
        }

        if (arImagePose != null) {
            drawArtwork(viewMtx, projMtx)
            calculateAndReportBounds(viewMtx, projMtx)
        }
    }

    /**
     * Projects the 4 corners of the quad to screen space and reports the bounding box.
     */
    private fun calculateAndReportBounds(viewMtx: FloatArray, projMtx: FloatArray) {
        val pose = arImagePose ?: return
        // Bolt Optimization: Reuse matrix
        System.arraycopy(pose, 0, calculationModelMatrix, 0, 16)
        val modelMtx = calculationModelMatrix

        // Apply same transforms as drawArtwork
        Matrix.rotateM(modelMtx, 0, rotationZ, 0f, 0f, 1f)
        Matrix.rotateM(modelMtx, 0, rotationX, 1f, 0f, 0f)
        Matrix.rotateM(modelMtx, 0, rotationY, 0f, 1f, 0f)
        Matrix.rotateM(modelMtx, 0, -90f, 1f, 0f, 0f)
        Matrix.scaleM(modelMtx, 0, scale, scale, 1f)
        val aspectRatio = if (overlayBitmap != null && overlayBitmap!!.height > 0) overlayBitmap!!.width.toFloat() / overlayBitmap!!.height.toFloat() else 1f
        Matrix.scaleM(modelMtx, 0, aspectRatio, 1f, 1f)

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        // Bolt Optimization: Loop unrolled / flat array traversal to avoid allocations
        for (i in 0 until 4) {
            val offset = i * 4
            // Model -> World -> View -> Clip
            // Note: Matrix.multiplyMV takes offset for input vector, but we must use calculationTempVec from index 0
            Matrix.multiplyMV(calculationTempVec, 0, modelMtx, 0, boundsCorners, offset) // World
            Matrix.multiplyMV(calculationResVec, 0, viewMtx, 0, calculationTempVec, 0) // View
            Matrix.multiplyMV(calculationTempVec, 0, projMtx, 0, calculationResVec, 0) // Clip

            if (calculationTempVec[3] != 0f) {
                // NDC -> Screen
                val ndcX = calculationTempVec[0] / calculationTempVec[3]
                val ndcY = calculationTempVec[1] / calculationTempVec[3]

                val screenX = (ndcX + 1f) / 2f * viewportWidth
                val screenY = (1f - ndcY) / 2f * viewportHeight

                minX = minOf(minX, screenX)
                minY = minOf(minY, screenY)
                maxX = maxOf(maxX, screenX)
                maxY = maxOf(maxY, screenY)
            }
        }

        calculationBounds.set(minX, minY, maxX, maxY)

        // Bolt Optimization: Only report bounds if they have changed significantly
        if (!hasReportedBounds ||
            abs(calculationBounds.left - lastReportedBounds.left) > BOUNDS_UPDATE_THRESHOLD ||
            abs(calculationBounds.top - lastReportedBounds.top) > BOUNDS_UPDATE_THRESHOLD ||
            abs(calculationBounds.right - lastReportedBounds.right) > BOUNDS_UPDATE_THRESHOLD ||
            abs(calculationBounds.bottom - lastReportedBounds.bottom) > BOUNDS_UPDATE_THRESHOLD
        ) {
            hasReportedBounds = true
            lastReportedBounds.set(calculationBounds)
            val boundsToSend = RectF(calculationBounds)
            mainHandler.post { onBoundsUpdated(boundsToSend) }
        }
    }

    private fun drawArtwork(viewMtx: FloatArray, projMtx: FloatArray) {
        val bitmap = overlayBitmap ?: return
        val pose = arImagePose ?: return
        // Bolt Optimization: Reuse matrix
        System.arraycopy(pose, 0, calculationModelMatrix, 0, 16)
        val modelMtx = calculationModelMatrix

        Matrix.rotateM(modelMtx, 0, rotationZ, 0f, 0f, 1f)
        Matrix.rotateM(modelMtx, 0, rotationX, 1f, 0f, 0f)
        Matrix.rotateM(modelMtx, 0, rotationY, 0f, 1f, 0f)
        Matrix.rotateM(modelMtx, 0, -90f, 1f, 0f, 0f)
        Matrix.scaleM(modelMtx, 0, scale, scale, 1f)

        val aspectRatio = if (bitmap.height > 0) bitmap.width.toFloat() / bitmap.height.toFloat() else 1f
        Matrix.scaleM(modelMtx, 0, aspectRatio, 1f, 1f)

        simpleQuadRenderer.draw(
            modelMtx, viewMtx, projMtx,
            bitmap, opacity, brightness, colorBalanceR, colorBalanceG, colorBalanceB,
            if (isDepthSupported) depthTextureId else -1
        )
    }

    private fun handleTap(frame: Frame) {
        val tap = tapQueue.poll() ?: return
        val hitResult = frame.hitTest(tap.first, tap.second)
        for (hit in hitResult) {
            val trackable = hit.trackable
            if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                activeAnchor = hit.createAnchor()
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
                activeAnchor?.detach()
                activeAnchor = hit.createAnchor()

                // Bolt Optimization: Reuse matrix
                hit.hitPose.toMatrix(calculationPoseMatrix, 0)
                arImagePose = calculationPoseMatrix
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

    private fun analyzeFrameAsync(frame: Frame) {
        val image = try {
            frame.acquireCameraImage()
        } catch (e: NotYetAvailableException) {
            return
        } catch (e: Exception) {
            return
        }

        try {
            val rotation = getRotationDegrees()
            val yBuffer = image.planes[0].buffer
            val width = image.width
            val height = image.height
            val rowStride = image.planes[0].rowStride
            val yMat = Mat(height, width, CvType.CV_8UC1, yBuffer, rowStride.toLong())
            val tempMat = Mat()
            yMat.copyTo(tempMat)
            val data = ByteArray((tempMat.total() * tempMat.channels()).toInt())
            tempMat.get(0, 0, data)
            tempMat.release()
            yMat.release()
            image.close()

            analysisScope.launch {
                try {
                    val processingMat = Mat(height, width, CvType.CV_8UC1)
                    processingMat.put(0, 0, data)
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

                    val descriptors = Mat()
                    val keypoints = MatOfKeyPoint()
                    synchronized(orb) {
                        orb.detectAndCompute(rotatedMat, Mat(), keypoints, descriptors)
                    }

                    val targetDescriptors = originalDescriptors
                    if (descriptors.rows() > 0 && targetDescriptors != null && !targetDescriptors.empty()) {
                        val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
                        val matches = MatOfDMatch()
                        matcher.match(descriptors, targetDescriptors, matches)
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
                    Log.e(TAG, "Analysis error", e)
                }
            }
        } catch (e: Exception) {
            image.close()
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
        return ((90 - displayRotation + 360) % 360).toFloat()
    }

    fun setFlashlight(enabled: Boolean) {
        sessionLock.lock()
        try {
            val session = this.session ?: return
            val config = session.config
            config.flashMode = if (enabled) Config.FlashMode.TORCH else Config.FlashMode.OFF
            session.configure(config)
        } catch (e: Exception) {
            Log.e(TAG, "Flashlight error", e)
        } finally {
            sessionLock.unlock()
        }
    }

    fun triggerCapture() {
        captureNextFrame = true
    }

    fun onResume(activity: Activity) {
        sessionLock.lock()
        try {
            if (session == null) {
                if (ArCoreApk.getInstance().requestInstall(activity, true) == ArCoreApk.InstallStatus.INSTALLED) {
                    session = Session(context)
                    val config = Config(session)
                    config.updateMode = Config.UpdateMode.BLOCKING
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    config.focusMode = Config.FocusMode.AUTO
                    if (session!!.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        config.depthMode = Config.DepthMode.AUTOMATIC
                        isDepthSupported = true
                    }

                    initialAugmentedImages?.let { images ->
                        if (images.isNotEmpty()) {
                            val database = AugmentedImageDatabase(session)
                            images.forEachIndexed { index, bitmap ->
                                val resized = com.hereliesaz.graffitixr.utils.resizeBitmapForArCore(bitmap)
                                database.addImage("target_$index", resized)
                            }
                            config.augmentedImageDatabase = database
                        }
                    }

                    session!!.configure(config)
                }
            }
            session?.resume()
            displayRotationHelper.onResume()
        } catch (e: Exception) {
            Log.e(TAG, "Resume error", e)
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
            Log.e(TAG, "Pause error", e)
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
            Log.e(TAG, "Cleanup error", e)
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
                Log.e(TAG, "Overlay update error", e)
            }
        }
    }

    companion object {
        const val TAG = "ArRenderer"
    }
}