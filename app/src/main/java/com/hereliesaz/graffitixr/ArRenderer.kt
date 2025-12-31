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
import com.google.ar.core.Earth
import com.google.ar.core.Frame
import com.google.ar.core.GeospatialPose
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
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
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
    private val isAnalyzing = AtomicBoolean(false)
    private val analysisLock = ReentrantLock()
    private var analysisBuffer: ByteArray? = null

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
    @Volatile var guideBitmap: Bitmap? = null
    @Volatile var showGuide: Boolean = false
    @Volatile var arImagePose: FloatArray? = null
    @Volatile var arState: ArState = ArState.SEARCHING
    private var activeAnchor: Anchor? = null

    // Bolt Optimization: Cached bitmap for capture to avoid repeated large allocations
    private var cachedCaptureBitmap: Bitmap? = null

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
    var isAnchorReplacementAllowed: Boolean = false

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
    private val calculationModelViewMatrix = FloatArray(16)
    private val calculationMvpMatrix = FloatArray(16)
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
    // Bolt Optimization: Reusable OpenCV objects to avoid allocations in analysis loop
    private val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
    private val analysisRawMat = Mat()
    private val analysisRotatedMat = Mat()
    private val analysisDescriptors = Mat()
    private val analysisKeypoints = MatOfKeyPoint()
    private val analysisMatches = MatOfDMatch()
    private val analysisMask = Mat()

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

        camera.getProjectionMatrix(projMtx, 0, 0.01f, 100.0f)
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

        if (activeAnchor == null || isAnchorReplacementAllowed) {
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
            updateModelMatrix()
            // Bolt Optimization: Calculate MVP once per frame
            Matrix.multiplyMM(calculationModelViewMatrix, 0, viewMtx, 0, calculationModelMatrix, 0)
            Matrix.multiplyMM(calculationMvpMatrix, 0, projMtx, 0, calculationModelViewMatrix, 0)

            drawArtwork(calculationMvpMatrix, calculationModelViewMatrix)
            calculateAndReportBounds(calculationMvpMatrix)
        }
    }

    private fun updateModelMatrix() {
        val pose = arImagePose ?: return
        // Bolt Optimization: Reuse matrix
        System.arraycopy(pose, 0, calculationModelMatrix, 0, 16)
        val modelMtx = calculationModelMatrix

        Matrix.rotateM(modelMtx, 0, rotationZ, 0f, 0f, 1f)
        Matrix.rotateM(modelMtx, 0, rotationX, 1f, 0f, 0f)
        Matrix.rotateM(modelMtx, 0, rotationY, 0f, 1f, 0f)
        Matrix.rotateM(modelMtx, 0, -90f, 1f, 0f, 0f)

        Matrix.scaleM(modelMtx, 0, scale, scale, 1f)

        val bitmap = if (showGuide) guideBitmap else overlayBitmap
        val aspectRatio = if (bitmap != null && bitmap.height > 0) bitmap.width.toFloat() / bitmap.height.toFloat() else 1f
        Matrix.scaleM(modelMtx, 0, aspectRatio, 1f, 1f)
    }

    /**
     * Projects the 4 corners of the quad to screen space and reports the bounding box.
     */
    private fun calculateAndReportBounds(mvpMtx: FloatArray) {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        // Bolt Optimization: Loop unrolled / flat array traversal to avoid allocations
        for (i in 0 until 4) {
            val offset = i * 4
            // Clip = MVP * Object
            // Note: Matrix.multiplyMV takes offset for input vector, but we must use calculationTempVec from index 0
            Matrix.multiplyMV(calculationTempVec, 0, mvpMtx, 0, boundsCorners, offset)

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

    private fun drawArtwork(mvpMatrix: FloatArray, modelViewMatrix: FloatArray) {
        val bitmap = if (showGuide) guideBitmap else overlayBitmap
        if (bitmap == null) return
        // Bolt Optimization: use pre-calculated calculationModelMatrix from updateModelMatrix()

        // Force full opacity for guide
        val drawOpacity = if (showGuide) 1.0f else opacity

        simpleQuadRenderer.draw(
            mvpMatrix, modelViewMatrix,
            bitmap, drawOpacity, brightness, colorBalanceR, colorBalanceG, colorBalanceB,
            if (isDepthSupported) depthTextureId else -1
        )
    }

    private fun handleTap(frame: Frame) {
        val tap = tapQueue.poll() ?: return
        val hitResult = frame.hitTest(tap.first, tap.second)
        for (hit in hitResult) {
            val trackable = hit.trackable
            if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                activeAnchor?.detach()
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
                val width = image.width
                val height = image.height

                // Bolt Optimization: Reuse bitmap for YUV conversion to avoid massive allocations
                if (cachedCaptureBitmap == null || cachedCaptureBitmap!!.width != width || cachedCaptureBitmap!!.height != height) {
                    cachedCaptureBitmap?.recycle()
                    cachedCaptureBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                }

                val rawBitmap = cachedCaptureBitmap!!
                yuvToRgbConverter.yuvToRgb(image, rawBitmap)

                val rotation = getRotationDegrees()
                val bitmap = if (rotation != 0f) {
                    val rotated = BitmapUtils.rotateBitmap(rawBitmap, rotation)
                    // Note: rawBitmap is reused, so we don't recycle it here
                    rotated
                } else {
                    // Deep copy because rawBitmap is reused
                    val srcConfig = rawBitmap.config
                    val targetConfig = when (srcConfig) {
                        null, Bitmap.Config.HARDWARE -> Bitmap.Config.ARGB_8888
                        else -> srcConfig
                    }
                    rawBitmap.copy(targetConfig, true)
                }
                mainHandler.post { onFrameCaptured(bitmap) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed", e)
        }
    }

    private fun analyzeFrameAsync(frame: Frame) {
        if (isAnalyzing.getAndSet(true)) return

        val image = try {
            frame.acquireCameraImage()
        } catch (e: Exception) {
            isAnalyzing.set(false)
            return
        }

        try {
            val rotation = getRotationDegrees()
            val plane = image.planes[0]
            val buffer = plane.buffer
            val width = image.width
            val height = image.height
            val rowStride = plane.rowStride

            val requiredSize = width * height
            if (analysisBuffer == null || analysisBuffer!!.size != requiredSize) {
                analysisBuffer = ByteArray(requiredSize)
            }
            val data = analysisBuffer!!

            buffer.rewind()
            if (width == rowStride) {
                buffer.get(data, 0, requiredSize)
            } else {
                for (row in 0 until height) {
                    buffer.position(row * rowStride)
                    buffer.get(data, row * width, width)
                }
            }

            image.close()

            analysisScope.launch {
                // Bolt Optimization: Lock to prevent race condition with cleanup
                analysisLock.lock()
                try {
                    analysisRawMat.create(height, width, CvType.CV_8UC1)
                    analysisRawMat.put(0, 0, data)

                    val finalMat = if (rotation != 0f) {
                        val rotateCode = when (rotation) {
                            90f -> Core.ROTATE_90_CLOCKWISE
                            180f -> Core.ROTATE_180
                            270f -> Core.ROTATE_90_COUNTERCLOCKWISE
                            else -> -1
                        }
                        if (rotateCode != -1) {
                            Core.rotate(analysisRawMat, analysisRotatedMat, rotateCode)
                            analysisRotatedMat
                        } else {
                            analysisRawMat
                        }
                    } else {
                        analysisRawMat
                    }

                    // Note: detectAndCompute will create/resize keypoints/descriptors as needed
                    synchronized(orb) {
                        orb.detectAndCompute(finalMat, analysisMask, analysisKeypoints, analysisDescriptors)
                    }

                    val targetDescriptors = originalDescriptors
                    if (analysisDescriptors.rows() > 0 && targetDescriptors != null && !targetDescriptors.empty()) {
                        matcher.match(analysisDescriptors, targetDescriptors, analysisMatches)

                        // Bolt Optimization: Read directly into float buffer to avoid creating DMatch objects and array
                        val totalMatches = analysisMatches.rows()
                        var goodMatches = 0
                        if (totalMatches > 0) {
                            val count = totalMatches * 4
                            // Allocating one float array is much cheaper than toArray() which allocates N DMatch objects
                            val matchData = FloatArray(count)
                            analysisMatches.get(0, 0, matchData)

                            for (i in 0 until totalMatches) {
                                // DMatch struct: queryIdx, trainIdx, imgIdx, distance
                                val distance = matchData[i * 4 + 3]
                                if (distance < 60) {
                                    goodMatches++
                                }
                            }
                        }

                        val ratio = if (originalKeypointCount > 0) {
                            goodMatches.toFloat() / originalKeypointCount.toFloat()
                        } else {
                            0f
                        }
                        val progress = (1.0f - ratio).coerceIn(0f, 1f) * 100f
                        mainHandler.post { onProgressUpdated(progress, null) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Analysis error", e)
                } finally {
                    analysisLock.unlock()
                    isAnalyzing.set(false)
                }
            }
        } catch (e: Exception) {
            image.close()
            isAnalyzing.set(false)
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

                    // Enable Geospatial Mode
                    if (session!!.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) {
                        config.geospatialMode = Config.GeospatialMode.ENABLED
                    }

                    // Enable Local Anchor Persistence for Multi-Point Calibration
                    // TODO: Enable this when API dependency resolves correctly.
                    // config.anchorPersistenceMode = Config.AnchorPersistenceMode.LOCAL

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

            // Bolt Optimization: Lock to ensure analysis is not running before releasing
            analysisLock.lock()
            try {
                analysisRawMat.release()
                analysisRotatedMat.release()
                analysisDescriptors.release()
                analysisKeypoints.release()
                analysisMatches.release()
                analysisMask.release()
                matcher.clear() // Release matcher resources (Java wrapper usually handles basic release via clear or finalize, but clear is safe)
            } finally {
                analysisLock.unlock()
            }
            // Bolt Optimization: Recycle cached capture bitmap
            cachedCaptureBitmap?.recycle()
            cachedCaptureBitmap = null

        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error", e)
        } finally {
            session = null
            sessionLock.unlock()
        }
    }

    fun resetAnchor() {
        sessionLock.lock()
        try {
            activeAnchor?.detach()
            activeAnchor = null
            arImagePose = null
            arState = ArState.SEARCHING
        } finally {
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

    fun createGeospatialAnchor(lat: Double, lng: Double, alt: Double, headingDegrees: Double) {
        val session = this.session ?: return
        val earth = session.earth ?: return

        // Convert heading (degrees) to Quaternion (rotation around Y - Up)
        // Heading is typically clockwise from North. GL rotation is CCW.
        val angleRad = Math.toRadians(-headingDegrees)
        val halfAngle = angleRad / 2.0
        val qx = 0.0f
        val qy = Math.sin(halfAngle).toFloat()
        val qz = 0.0f
        val qw = Math.cos(halfAngle).toFloat()

        sessionLock.lock()
        try {
            if (earth.trackingState == TrackingState.TRACKING) {
                activeAnchor?.detach()
                activeAnchor = earth.createAnchor(lat, lng, alt, qx, qy, qz, qw)
                arState = ArState.PLACED
                mainHandler.post { onAnchorCreated() }
            } else {
                Log.w(TAG, "Earth not tracking, cannot create geospatial anchor.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create geospatial anchor", e)
        } finally {
            sessionLock.unlock()
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