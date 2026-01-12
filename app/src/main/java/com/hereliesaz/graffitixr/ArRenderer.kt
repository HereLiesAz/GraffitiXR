package com.hereliesaz.graffitixr

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
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
import com.google.ar.core.exceptions.SessionPausedException
import com.hereliesaz.graffitixr.data.Fingerprint
import com.hereliesaz.graffitixr.data.OverlayLayer
import com.hereliesaz.graffitixr.rendering.BackgroundRenderer
import com.hereliesaz.graffitixr.rendering.PlaneRenderer
import com.hereliesaz.graffitixr.rendering.PointCloudRenderer
import com.hereliesaz.graffitixr.rendering.SimpleQuadRenderer
import com.hereliesaz.graffitixr.utils.BitmapUtils
import com.hereliesaz.graffitixr.utils.DisplayRotationHelper
import com.hereliesaz.graffitixr.utils.YuvToRgbConverter
import com.hereliesaz.graffitixr.utils.enhanceImageForAr
import com.hereliesaz.graffitixr.utils.ensureOpenCVLoaded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/**
 * Custom GLSurfaceView.Renderer that handles the ARCore session and OpenGL ES rendering.
 */
class ArRenderer(
    private val context: Context,
    private val onPlanesDetected: (Boolean) -> Unit,
    private val onFrameCaptured: (Bitmap) -> Unit,
    private val onAnchorCreated: () -> Unit,
    private val onProgressUpdated: (Float, Bitmap?) -> Unit,
    private val onTrackingFailure: (String?) -> Unit,
    private val onBoundsUpdated: (RectF) -> Unit
) : GLSurfaceView.Renderer {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var wasTracking = false
    private val sessionLock = ReentrantLock()
    private val isAnalyzing = AtomicBoolean(false)
    private var analysisBuffer: ByteArray? = null

    @Volatile var session: Session? = null
    @Volatile private var isSessionResumed = false
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

    // Multi-Layer Support
    private val layerBitmaps = ConcurrentHashMap<String, Bitmap>()
    @Volatile private var _layers: List<OverlayLayer> = emptyList()
    @Volatile var activeLayerId: String? = null
    @Volatile var overlayBitmap: Bitmap? = null

    @Volatile var guideBitmap: Bitmap? = null
    @Volatile var showGuide: Boolean = false
    
    @Volatile var arImagePose: FloatArray? = null
    @Volatile var arState: ArState = ArState.SEARCHING
    private var activeAnchor: Anchor? = null

    private var cachedCaptureBitmap: Bitmap? = null
    private var initialAugmentedImages: List<Bitmap>? = null
    private var configJob: Job? = null
    private var resumeJob: Job? = null

    // Legacy Transforms
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

    // Reusable matrices
    private val worldPos = FloatArray(4)
    private val eyePos = FloatArray(4)
    private val clipPos = FloatArray(4)
    private val viewMtx = FloatArray(16)
    private val projMtx = FloatArray(16)
    private val calculationModelMatrix = FloatArray(16)
    private val calculationModelViewMatrix = FloatArray(16)
    private val calculationMvpMatrix = FloatArray(16)
    private val calculationPoseMatrix = FloatArray(16)
    private val calculationTempVec = FloatArray(4)

    private val displayToCameraTransform = FloatArray(9)
    private val coordsViewport = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f)
    private val coordsTexture = FloatArray(6)

    private val calculationBounds = RectF()
    private val lastReportedBounds = RectF()
    private var hasReportedBounds = false
    private val BOUNDS_UPDATE_THRESHOLD_VAL = 2f

    private val boundsCorners = floatArrayOf(
        -0.5f, -0.5f, 0f, 1f,
        -0.5f, 0.5f, 0f, 1f,
        0.5f, 0.5f, 0f, 1f,
        0.5f, -0.5f, 0f, 1f
    )

    @Volatile private var fingerprintData: Fingerprint? = null
    private var originalKeypointCount: Int = 0
    private var lastAnalysisTime = 0L
    private val ANALYSIS_INTERVAL_MS = 1500L
    private val analysisScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun setFingerprint(fingerprint: Fingerprint) {
        this.fingerprintData = fingerprint
        this.originalKeypointCount = fingerprint.keypoints.size
    }

    fun setAugmentedImageDatabase(bitmaps: List<Bitmap>) {
        this.initialAugmentedImages = bitmaps

        if (session != null && isSessionResumed) {
            configJob?.cancel()
            configJob = CoroutineScope(Dispatchers.Main).launch {
                val session = this@ArRenderer.session ?: return@launch
                val database = configureAugmentedImageDatabase(session, bitmaps)

                if (database != null) {
                    sessionLock.lock()
                    try {
                        if (this@ArRenderer.session == session) {
                            val config = session.config
                            config.augmentedImageDatabase = database
                            session.configure(config)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to configure AugmentedImageDatabase dynamically", e)
                    } finally {
                        sessionLock.unlock()
                    }
                }
            }
        }
    }

    private suspend fun configureAugmentedImageDatabase(session: Session, bitmaps: List<Bitmap>): AugmentedImageDatabase? {
        val database = AugmentedImageDatabase(session)
        var imagesAdded = 0
        withContext(Dispatchers.IO) {
            bitmaps.forEachIndexed { index, bitmap ->
                try {
                    val resized = com.hereliesaz.graffitixr.utils.resizeBitmapForArCore(bitmap)
                    try {
                        database.addImage("target_$index", resized)
                        imagesAdded++
                    } catch (e: com.google.ar.core.exceptions.ImageInsufficientQualityException) {
                        Log.w(TAG, "Image quality low for target_$index. Attempting OpenCV enhancement...")
                        val enhanced = enhanceImageForAr(resized)
                        try {
                            database.addImage("target_$index", enhanced)
                            imagesAdded++
                        } catch (e2: com.google.ar.core.exceptions.ImageInsufficientQualityException) {
                            Log.e(TAG, "Image still insufficient quality after enhancement: target_$index")
                            mainHandler.post { onTrackingFailure("Target image quality is too low for reliable tracking. Please capture a sharper image with more high-contrast details.") }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding image to database: target_$index", e)
                }
            }
        }
        return if (imagesAdded > 0) database else null
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
        
        // Critical: Safety check for session state to prevent scheduler.cc RET_CHECK failure
        if (!isSessionResumed) return
        
        sessionLock.lock()
        try {
            val currentSession = session
            if (currentSession == null) return

            if (backgroundRenderer.textureId != -1) {
                currentSession.setCameraTextureName(backgroundRenderer.textureId)
            }
            displayRotationHelper.updateSessionIfNeeded(currentSession)

            try {
                val frame = currentSession.update()
                if (isDepthSupported) {
                    updateDepthTexture(frame)
                }

                frame.transformCoordinates2d(
                    com.google.ar.core.Coordinates2d.VIEW_NORMALIZED,
                    coordsViewport,
                    com.google.ar.core.Coordinates2d.TEXTURE_NORMALIZED,
                    coordsTexture
                )

                val u0 = coordsTexture[0]; val v0 = coordsTexture[1]
                val u1 = coordsTexture[2]; val v1 = coordsTexture[3]
                val u2 = coordsTexture[4]; val v2 = coordsTexture[5]

                val c = u0; val f = v0
                val a = u1 - u0; val d = v1 - v0
                val b = u2 - u0; val e = v2 - v0

                displayToCameraTransform[0] = a; displayToCameraTransform[1] = d; displayToCameraTransform[2] = 0f
                displayToCameraTransform[3] = b; displayToCameraTransform[4] = e; displayToCameraTransform[5] = 0f
                displayToCameraTransform[6] = c; displayToCameraTransform[7] = f; displayToCameraTransform[8] = 1f

                drawFrame(frame)
            } catch (e: SessionPausedException) {
                // Ignore
            } catch (e: Exception) {
                // Catch potential scheduler/mediapipe failures and log them, but don't crash
                Log.e(TAG, "Error updating AR frame: ${e.message}")
            }
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
        } catch (e: Exception) {}
    }

    private fun drawFrame(frame: Frame) {
        backgroundRenderer.draw(frame)

        if (captureNextFrame) {
            captureFrameForFingerprint(frame)
            captureNextFrame = false
        }

        if (System.currentTimeMillis() - lastAnalysisTime > ANALYSIS_INTERVAL_MS && fingerprintData != null) {
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

        if (activeAnchor == null && arState != ArState.PLACED) {
            val updatedAugmentedImages = frame.getUpdatedTrackables(AugmentedImage::class.java)
            for (img in updatedAugmentedImages) {
                if (img.trackingState == TrackingState.TRACKING && img.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING) {
                    try {
                        activeAnchor = img.createAnchor(img.centerPose)
                        arState = ArState.LOCKED
                        mainHandler.post { onAnchorCreated() }
                        break
                    } catch (e: Exception) {}
                }
            }
        }

        if (activeAnchor != null) {
            if (activeAnchor!!.trackingState == TrackingState.TRACKING) {
                activeAnchor!!.pose.toMatrix(calculationPoseMatrix, 0)
                arImagePose = calculationPoseMatrix.clone()
                handlePan(frame, viewMtx, projMtx)
            }
        }

        if (activeAnchor == null || isAnchorReplacementAllowed) {
            val currentSession = session
            if (currentSession != null) {
                val planes = currentSession.getAllTrackables(Plane::class.java)
                val (gridAlpha, gridWidth, outlineWidth) = when (arState) {
                    ArState.LOCKED -> Triple(0.0f, 0.0f, 0.0f)
                    ArState.PLACED -> Triple(0.3f, 0.005f, 2.0f)
                    else -> Triple(1.0f, 0.02f, 5.0f)
                }
                val hasPlane = planeRenderer.drawPlanes(planes, viewMtx, projMtx, gridAlpha, gridWidth, outlineWidth)
                mainHandler.post { onPlanesDetected(hasPlane) }
                handleTap(frame)
            }
        }

        if (arImagePose != null) {
            if (showGuide) {
                drawLayer(guideBitmap, 1.0f, 0f, 0f, 0f, 0f, 0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, false, -1, androidx.compose.ui.graphics.BlendMode.SrcOver)
            } else {
                val currentLayers = _layers
                currentLayers.forEachIndexed { index, layer ->
                    if (layer.isVisible) {
                        val bitmap = layerBitmaps[layer.id]
                        if (bitmap != null) {
                            drawLayer(bitmap, layer.scale, layer.rotationX, layer.rotationY, layer.rotationZ, layer.offset.x, layer.offset.y, layer.opacity, layer.brightness, layer.colorBalanceR, layer.colorBalanceG, layer.colorBalanceB, layer.id == activeLayerId, index, layer.blendMode)
                        }
                    }
                }
                if (currentLayers.isEmpty() && overlayBitmap != null) {
                    drawLayer(overlayBitmap, scale, rotationX, rotationY, rotationZ, 0f, 0f, opacity, brightness, colorBalanceR, colorBalanceG, colorBalanceB, true, 0, androidx.compose.ui.graphics.BlendMode.SrcOver)
                }
            }
        }
    }

    private fun drawLayer(bitmap: Bitmap?, scale: Float, rotX: Float, rotY: Float, rotZ: Float, offsetX: Float, offsetY: Float, opacity: Float, brightness: Float, colorR: Float, colorG: Float, colorB: Float, reportBounds: Boolean, layerIndex: Int, blendMode: androidx.compose.ui.graphics.BlendMode) {
        if (bitmap == null) return
        val pose = arImagePose ?: return
        System.arraycopy(pose, 0, calculationModelMatrix, 0, 16)
        val modelMtx = calculationModelMatrix
        if (arState == ArState.PLACED) Matrix.rotateM(modelMtx, 0, -90f, 1f, 0f, 0f)
        Matrix.rotateM(modelMtx, 0, rotZ, 0f, 0f, 1f)
        Matrix.rotateM(modelMtx, 0, rotX, 1f, 0f, 0f)
        Matrix.rotateM(modelMtx, 0, rotY, 0f, 1f, 0f)
        val zOffset = if (layerIndex >= 0) layerIndex * 0.001f else 0f
        Matrix.translateM(modelMtx, 0, offsetX, offsetY, zOffset)
        Matrix.scaleM(modelMtx, 0, scale, scale, 1f)
        val aspectRatio = if (bitmap.height > 0) bitmap.width.toFloat() / bitmap.height.toFloat() else 1f
        Matrix.scaleM(modelMtx, 0, aspectRatio, 1f, 1f)
        Matrix.multiplyMM(calculationModelViewMatrix, 0, viewMtx, 0, modelMtx, 0)
        Matrix.multiplyMM(calculationMvpMatrix, 0, projMtx, 0, calculationModelViewMatrix, 0)
        simpleQuadRenderer.draw(calculationMvpMatrix, calculationModelViewMatrix, bitmap, opacity, brightness, colorR, colorG, colorB, if (isDepthSupported) depthTextureId else -1, backgroundRenderer.textureId, viewportWidth.toFloat(), viewportHeight.toFloat(), displayToCameraTransform, blendMode)
        if (reportBounds) calculateAndReportBounds(calculationMvpMatrix)
    }

    private fun calculateAndReportBounds(mvpMtx: FloatArray) {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = Float.MIN_VALUE; var maxY = Float.MIN_VALUE
        for (i in 0 until 4) {
            val offset = i * 4
            Matrix.multiplyMV(calculationTempVec, 0, mvpMtx, 0, boundsCorners, offset)
            if (calculationTempVec[3] != 0f) {
                val screenX = (calculationTempVec[0] / calculationTempVec[3] + 1f) / 2f * viewportWidth
                val screenY = (1f - calculationTempVec[1] / calculationTempVec[3]) / 2f * viewportHeight
                minX = minOf(minX, screenX); minY = minY.coerceAtMost(screenY)
                maxX = maxX.coerceAtLeast(screenX); maxY = maxY.coerceAtLeast(screenY)
            }
        }
        calculationBounds.set(minX, minY, maxX, maxY)
        if (!hasReportedBounds || abs(calculationBounds.left - lastReportedBounds.left) > BOUNDS_UPDATE_THRESHOLD_VAL || abs(calculationBounds.top - lastReportedBounds.top) > BOUNDS_UPDATE_THRESHOLD_VAL || abs(calculationBounds.right - lastReportedBounds.right) > BOUNDS_UPDATE_THRESHOLD_VAL || abs(calculationBounds.bottom - lastReportedBounds.bottom) > BOUNDS_UPDATE_THRESHOLD_VAL) {
            hasReportedBounds = true; lastReportedBounds.set(calculationBounds)
            val boundsToSend = RectF(calculationBounds)
            mainHandler.post { onBoundsUpdated(boundsToSend) }
        }
    }

    private fun handleTap(frame: Frame) {
        val tap = tapQueue.poll() ?: return
        val hitResult = frame.hitTest(tap.first, tap.second)
        for (hit in hitResult) {
            val trackable = hit.trackable
            if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                val camFwdX = -viewMtx[2]; val camFwdY = -viewMtx[6]; val camFwdZ = -viewMtx[10]
                hit.hitPose.toMatrix(calculationPoseMatrix, 0)
                val dot = camFwdX * calculationPoseMatrix[4] + camFwdY * calculationPoseMatrix[5] + camFwdZ * calculationPoseMatrix[6]
                if (abs(dot) > 0.7f) {
                    try { activeAnchor?.detach(); activeAnchor = hit.createAnchor(); arState = ArState.PLACED; mainHandler.post { onAnchorCreated() }; break } catch (e: Exception) {}
                }
            }
        }
    }

    private fun handlePan(frame: Frame, viewMtx: FloatArray, projMtx: FloatArray) {
        var dx = 0f; var dy = 0f
        synchronized(panLock) { dx = pendingPanX; dy = pendingPanY; pendingPanX = 0f; pendingPanY = 0f }
        if (dx == 0f && dy == 0f) return
        val pose = arImagePose ?: return
        val screenPos = projectPoseToScreen(pose, viewMtx, projMtx) ?: return
        val newX = screenPos.first + dx; val newY = screenPos.second + dy
        val hitResult = frame.hitTest(newX, newY)
        for (hit in hitResult) {
            if (hit.trackable is Plane && (hit.trackable as Plane).isPoseInPolygon(hit.hitPose)) {
                try { activeAnchor?.detach(); activeAnchor = hit.createAnchor(); val previousState = arState; arState = ArState.PLACED; if (previousState != ArState.PLACED) mainHandler.post { onAnchorCreated() }; hit.hitPose.toMatrix(calculationPoseMatrix, 0); arImagePose = calculationPoseMatrix.clone(); break } catch (e: Exception) {}
            }
        }
    }

    private fun projectPoseToScreen(modelMtx: FloatArray, viewMtx: FloatArray, projMtx: FloatArray): Pair<Float, Float>? {
        worldPos[0] = modelMtx[12]; worldPos[1] = modelMtx[13]; worldPos[2] = modelMtx[14]; worldPos[3] = 1.0f
        Matrix.multiplyMV(eyePos, 0, viewMtx, 0, worldPos, 0)
        Matrix.multiplyMV(clipPos, 0, projMtx, 0, eyePos, 0)
        if (clipPos[3] == 0f) return null
        return Pair((clipPos[0] / clipPos[3] + 1f) / 2f * viewportWidth, (1f - clipPos[1] / clipPos[3]) / 2f * viewportHeight)
    }

    private fun captureFrameForFingerprint(frame: Frame) {
        try {
            frame.acquireCameraImage().use { image ->
                val width = image.width; val height = image.height
                if (cachedCaptureBitmap == null || cachedCaptureBitmap!!.width != width || cachedCaptureBitmap!!.height != height) {
                    cachedCaptureBitmap?.recycle(); cachedCaptureBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                }
                val rawBitmap = cachedCaptureBitmap!!
                yuvToRgbConverter.yuvToRgb(image, rawBitmap)
                val rotation = getRotationDegrees()
                val bitmap = if (rotation != 0f) BitmapUtils.rotateBitmap(rawBitmap, rotation) else rawBitmap.copy(Bitmap.Config.ARGB_8888, true)
                mainHandler.post { onFrameCaptured(bitmap) }
            }
        } catch (e: Exception) {}
    }

    private fun analyzeFrameAsync(frame: Frame) {
        if (isAnalyzing.getAndSet(true)) return
        
        // --- CRITICAL CHECK: OpenCV Availability ---
        if (!ensureOpenCVLoaded()) {
            isAnalyzing.set(false)
            return
        }

        val image = try { frame.acquireCameraImage() } catch (e: Exception) { isAnalyzing.set(false); return }

        try {
            val rotation = getRotationDegrees()
            val plane = image.planes[0]; val buffer = plane.buffer
            val width = image.width; val height = image.height; val rowStride = plane.rowStride
            if (analysisBuffer == null || analysisBuffer!!.size != width * height) analysisBuffer = ByteArray(width * height)
            val data = analysisBuffer!!
            buffer.rewind()
            if (width == rowStride) buffer.get(data) else {
                for (row in 0 until height) { buffer.position(row * rowStride); buffer.get(data, row * width, width) }
            }
            image.close()

            analysisScope.launch {
                // Ensure native library is still loaded in this coroutine thread
                if (!ensureOpenCVLoaded()) { isAnalyzing.set(false); return@launch }
                
                // Bolt Optimization: Local Mat objects with explicit lifecycle
                val rawMat = Mat()
                val rotatedMat = Mat()
                val descriptors = Mat()
                val targetDescriptors = Mat()
                val keypoints = MatOfKeyPoint()
                val matches = MatOfDMatch()
                val mask = Mat()
                
                try {
                    rawMat.create(height, width, CvType.CV_8UC1)
                    rawMat.put(0, 0, data)

                    val finalMat = if (rotation != 0f) {
                        val code = when (rotation) {
                            90f -> Core.ROTATE_90_CLOCKWISE
                            180f -> Core.ROTATE_180
                            270f -> Core.ROTATE_90_COUNTERCLOCKWISE
                            else -> -1
                        }
                        if (code != -1) { Core.rotate(rawMat, rotatedMat, code); rotatedMat } else rawMat
                    } else rawMat

                    val orbLocal = ORB.create()
                    orbLocal.detectAndCompute(finalMat, mask, keypoints, descriptors)

                    val fingerprint = fingerprintData
                    if (descriptors.rows() > 0 && fingerprint != null && fingerprint.descriptorsData.isNotEmpty()) {
                        targetDescriptors.create(fingerprint.descriptorsRows, fingerprint.descriptorsCols, fingerprint.descriptorsType)
                        targetDescriptors.put(0, 0, fingerprint.descriptorsData)

                        val matcherLocal = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
                        matcherLocal.match(descriptors, targetDescriptors, matches)
                        
                        val total = matches.rows()
                        var good = 0
                        if (total > 0) {
                            val matchData = FloatArray(total * 4)
                            matches.get(0, 0, matchData)
                            for (i in 0 until total) { if (matchData[i * 4 + 3] < 60) good++ }
                        }
                        val ratio = if (originalKeypointCount > 0) good.toFloat() / originalKeypointCount.toFloat() else 0f
                        mainHandler.post { onProgressUpdated((1.0f - ratio).coerceIn(0f, 1f) * 100f, null) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Analysis error: ${e.message}")
                } finally {
                    // Bolt Optimization: Aggressive native resource release to prevent finalizer UnsatisfiedLinkError
                    // We check for valid native pointers before release to be extra safe
                    if (rawMat.nativeObj != 0L) rawMat.release()
                    if (rotatedMat.nativeObj != 0L) rotatedMat.release()
                    if (descriptors.nativeObj != 0L) descriptors.release()
                    if (targetDescriptors.nativeObj != 0L) targetDescriptors.release()
                    if (keypoints.nativeObj != 0L) keypoints.release()
                    if (matches.nativeObj != 0L) matches.release()
                    if (mask.nativeObj != 0L) mask.release()
                    isAnalyzing.set(false)
                }
            }
        } catch (e: Exception) { image.close(); isAnalyzing.set(false) }
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
        } catch (e: Exception) {} finally { sessionLock.unlock() }
    }

    fun triggerCapture() { captureNextFrame = true }

    fun onResume(activity: Activity) {
        displayRotationHelper.onResume()
        sessionLock.lock()
        try {
            if (session == null) {
                if (ArCoreApk.getInstance().requestInstall(activity, true) == ArCoreApk.InstallStatus.INSTALLED) {
                    session = Session(context)
                    val config = Config(session)
                    config.updateMode = Config.UpdateMode.BLOCKING
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    config.focusMode = Config.FocusMode.AUTO
                    val hasFineLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    config.geospatialMode = if (hasFineLocation && session!!.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) Config.GeospatialMode.ENABLED else Config.GeospatialMode.DISABLED
                    config.cloudAnchorMode = Config.CloudAnchorMode.ENABLED
                    config.depthMode = Config.DepthMode.DISABLED
                    session!!.configure(config)
                    initialAugmentedImages?.let { setAugmentedImageDatabase(it) }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Resume error", e) } finally { sessionLock.unlock() }

        resumeJob?.cancel()
        resumeJob = CoroutineScope(Dispatchers.IO).launch {
            sessionLock.lock()
            try { if (session != null && !isSessionResumed) { session!!.resume(); isSessionResumed = true } } catch (e: Exception) {} finally { sessionLock.unlock() }
        }
    }

    fun onPause() {
        resumeJob?.cancel(); isSessionResumed = false
        sessionLock.lock()
        try { configJob?.cancel(); configJob = null; displayRotationHelper.onPause(); session?.pause() } catch (e: Exception) {} finally { sessionLock.unlock() }
    }

    fun cleanup() {
        sessionLock.lock()
        try { configJob?.cancel(); analysisScope.cancel(); session?.close(); cachedCaptureBitmap?.recycle(); layerBitmaps.clear() } catch (e: Exception) {} finally { session = null; sessionLock.unlock() }
    }

    fun resetAnchor() {
        sessionLock.lock()
        try { activeAnchor?.detach(); activeAnchor = null; arImagePose = null; arState = ArState.SEARCHING } finally { sessionLock.unlock() }
    }

    fun queueTap(x: Float, y: Float) { tapQueue.offer(Pair(x, y)) }
    fun queuePan(dx: Float, dy: Float) { synchronized(panLock) { pendingPanX += dx; pendingPanY += dy } }

    fun createGeospatialAnchor(lat: Double, lng: Double, alt: Double, headingDegrees: Double) {
        val s = session ?: return; val earth = s.earth ?: return
        val angleRad = Math.toRadians(-headingDegrees); val halfAngle = angleRad / 2.0
        sessionLock.lock()
        try { if (earth.trackingState == TrackingState.TRACKING) {
            activeAnchor?.detach()
            activeAnchor = earth.createAnchor(lat, lng, alt, 0f, Math.sin(halfAngle).toFloat(), 0f, Math.cos(halfAngle).toFloat())
            arState = ArState.PLACED; mainHandler.post { onAnchorCreated() }
        } } catch (e: Exception) {} finally { sessionLock.unlock() }
    }

    fun updateOverlayImage(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = context.imageLoader.execute(ImageRequest.Builder(context).data(uri).allowHardware(false).build())
                val d = result.drawable
                if (d is android.graphics.drawable.BitmapDrawable) overlayBitmap = d.bitmap
            } catch (e: Exception) {}
        }
    }

    fun setLayers(newLayers: List<OverlayLayer>) {
        this._layers = newLayers
        CoroutineScope(Dispatchers.IO).launch {
            newLayers.forEach { layer ->
                if (!layerBitmaps.containsKey(layer.id)) {
                    try {
                        val result = context.imageLoader.execute(ImageRequest.Builder(context).data(layer.uri).allowHardware(false).build())
                        val d = result.drawable
                        if (d is android.graphics.drawable.BitmapDrawable) layerBitmaps[layer.id] = d.bitmap
                    } catch (e: Exception) {}
                }
            }
            val newIds = newLayers.map { it.id }.toSet()
            val it = layerBitmaps.iterator()
            while (it.hasNext()) { if (!newIds.contains(it.next().key)) it.remove() }
        }
    }

    companion object {
        const val TAG = "ArRenderer"
        const val BOUNDS_UPDATE_THRESHOLD = 2f
        init {
            // Bolt Optimization: Ensure native library is loaded for any thread accessing this class
            try {
                if (!OpenCVLoader.initLocal()) {
                    System.loadLibrary("opencv_java4")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Static OpenCV load failed in ArRenderer", e)
            }
        }
    }
}
