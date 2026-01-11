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
import com.google.ar.core.exceptions.NotYetAvailableException
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
 *
 * Updates:
 * - Removed all Mat members to prevent finalizer UnsatisfiedLinkError.
 * - Stores Fingerprint data as raw bytes/metadata to avoid native pointer leaks.
 * - Explicit local Mat management in analysis loop.
 * - Enhanced error handling for Augmented Image quality.
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
    
    /**
     * The current world pose of the project anchor.
     * Thread Safety: This is always a cloned array or a stable copy when set,
     * allowing external threads to read it (e.g., for serialization) without race conditions.
     */
    @Volatile var arImagePose: FloatArray? = null
    
    @Volatile var arState: ArState = ArState.SEARCHING
    private var activeAnchor: Anchor? = null

    // Bolt Optimization: Cached bitmap for capture to avoid repeated large allocations
    private var cachedCaptureBitmap: Bitmap? = null

    // Configuration for next init (Lazy Loading)
    private var initialAugmentedImages: List<Bitmap>? = null

    // Background job for configuring Augmented Images
    private var configJob: Job? = null

    // Background job for resuming the session
    private var resumeJob: Job? = null

    // Legacy / Global Transforms (Retained for guide or fallbacks, but layers use their own)
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

    // Matrices for Background Blending
    private val displayToCameraTransform = FloatArray(9) // 3x3 Matrix
    // We want to calculate the matrix that maps Shader UVs (Y-up, 0..1) to Camera Texture UVs.
    // We use 3 points to define the affine transform:
    // P0: Shader (0,0) [Bottom-Left] -> View (0,1) [Bottom-Left]
    // P1: Shader (1,0) [Bottom-Right] -> View (1,1) [Bottom-Right]
    // P2: Shader (0,1) [Top-Left]    -> View (0,0) [Top-Left]
    private val coordsViewport = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f)
    private val coordsTexture = FloatArray(6) // Resulting texture coords

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

    // Bolt Optimization: Replaced originalDescriptors Mat with raw data to prevent leaks
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
                // Configure in background
                val database = configureAugmentedImageDatabase(session, bitmaps)

                if (database != null) {
                    // Apply in session lock
                    sessionLock.lock()
                    try {
                        if (this@ArRenderer.session == session) {
                            val config = session.config
                            config.augmentedImageDatabase = database
                            session.configure(config)
                            Log.d(TAG, "Dynamic Update: AugmentedImageDatabase configured")
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

    /**
     * Creates and populates an AugmentedImageDatabase from a list of bitmaps.
     * Includes error handling for low-quality images.
     */
    private suspend fun configureAugmentedImageDatabase(session: Session, bitmaps: List<Bitmap>): AugmentedImageDatabase? {
        val database = AugmentedImageDatabase(session)
        var imagesAdded = 0
        withContext(Dispatchers.IO) {
            bitmaps.forEachIndexed { index, bitmap ->
                try {
                    val resized = com.hereliesaz.graffitixr.utils.resizeBitmapForArCore(bitmap)
                    database.addImage("target_$index", resized)
                    imagesAdded++
                } catch (e: com.google.ar.core.exceptions.ImageInsufficientQualityException) {
                    Log.e(TAG, "Image quality too low for AR tracking: target_$index", e)
                    mainHandler.post { onTrackingFailure("Target image quality is too low for reliable tracking. Try capturing a sharper image with more detail.") }
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding image to database: target_$index", e)
                }
            }
        }
        return if (imagesAdded > 0) database else null
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.d(TAG, "onSurfaceCreated: initializing renderers")
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        try {
            backgroundRenderer.createOnGlThread()
            planeRenderer.createOnGlThread()
            pointCloudRenderer.createOnGlThread()
            simpleQuadRenderer.createOnGlThread()
            createDepthTexture()
            Log.d(TAG, "onSurfaceCreated: renderers initialized")
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
        Log.d(TAG, "onSurfaceChanged: width=$width, height=$height")
        viewportWidth = width
        viewportHeight = height
        displayRotationHelper.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        sessionLock.lock()

        try {
            val currentSession = session
            if (currentSession == null || !isSessionResumed) {
                return
            }

            if (backgroundRenderer.textureId != -1) {
                currentSession.setCameraTextureName(backgroundRenderer.textureId)
            } else {
                Log.e(TAG, "onDrawFrame: Background renderer textureId is -1")
            }
            displayRotationHelper.updateSessionIfNeeded(currentSession)

            try {
                val frame = currentSession.update()
                if (isDepthSupported) {
                    updateDepthTexture(frame)
                }

                // Calculate Display -> Camera Transform (once per frame)
                // Map Shader UV points to Texture Coordinates
                frame.transformCoordinates2d(
                    com.google.ar.core.Coordinates2d.VIEW_NORMALIZED,
                    coordsViewport,
                    com.google.ar.core.Coordinates2d.TEXTURE_NORMALIZED,
                    coordsTexture
                )

                // Calculate Affine Matrix (3x3)
                // Maps (0,0) -> (u0, v0) = Translation (c, f)
                // Maps (1,0) -> (u1, v1)
                // Maps (0,1) -> (u2, v2)

                // Matrix M:
                // | a b c |
                // | d e f |
                // | 0 0 1 |
                //
                // x' = ax + by + c
                // y' = dx + ey + f

                val u0 = coordsTexture[0]; val v0 = coordsTexture[1]
                val u1 = coordsTexture[2]; val v1 = coordsTexture[3]
                val u2 = coordsTexture[4]; val v2 = coordsTexture[5]

                val c = u0
                val f = v0
                val a = u1 - u0
                val d = v1 - v0
                val b = u2 - u0
                val e = v2 - v0

                // Column-Major Order for OpenGL
                displayToCameraTransform[0] = a
                displayToCameraTransform[1] = d
                displayToCameraTransform[2] = 0f

                displayToCameraTransform[3] = b
                displayToCameraTransform[4] = e
                displayToCameraTransform[5] = 0f

                displayToCameraTransform[6] = c
                displayToCameraTransform[7] = f
                displayToCameraTransform[8] = 1f

                drawFrame(frame)
            } catch (e: SessionPausedException) {
                // Paused
            } catch (e: Exception) {
                Log.e(TAG, "Error updating AR frame", e)
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
        } catch (e: NotYetAvailableException) {
            // Ignore
        } catch (e: Exception) {
            Log.e(TAG, "Error updating depth texture", e)
        }
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
                Log.d(TAG, "Tracking started")
                mainHandler.post { onTrackingFailure(null) }
            }
        } else {
            if (wasTracking) {
                wasTracking = false
                Log.d(TAG, "Tracking lost")
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
                    try {
                        activeAnchor = img.createAnchor(img.centerPose)
                        arState = ArState.LOCKED
                        mainHandler.post { onAnchorCreated() }
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create anchor for augmented image", e)
                    }
                }
            }
        }

        if (activeAnchor != null) {
            if (activeAnchor!!.trackingState == TrackingState.TRACKING) {
                val pose = activeAnchor!!.pose
                pose.toMatrix(calculationPoseMatrix, 0)
                arImagePose = calculationPoseMatrix.clone()
                handlePan(frame, viewMtx, projMtx)
            }
        }

        if (activeAnchor == null || isAnchorReplacementAllowed) {
            val currentSession = session
            if (currentSession != null) {
                val planes = currentSession.getAllTrackables(Plane::class.java)

                // Determine plane visualization style based on state
                // If Target Created (LOCKED), we hide them completely (alpha 0, width 0)
                // If Anchor Placed (PLACED), we dim them and thin them
                // Otherwise (SEARCHING), full visibility
                val (gridAlpha, gridWidth, outlineWidth) = when (arState) {
                    ArState.LOCKED -> Triple(0.0f, 0.0f, 0.0f) // Hidden
                    ArState.PLACED -> Triple(0.3f, 0.005f, 2.0f) // Dimmed
                    else -> Triple(1.0f, 0.02f, 5.0f) // Default
                }

                // We always draw (even if invisible) to return hasPlane status correctly for UI
                // But we optimize by passing 0 alpha if hidden
                val hasPlane = planeRenderer.drawPlanes(
                    planes,
                    viewMtx,
                    projMtx,
                    gridAlpha,
                    gridWidth,
                    outlineWidth
                )

                mainHandler.post { onPlanesDetected(hasPlane) }
                handleTap(frame)
            }
        }

        if (arImagePose != null) {
            // Draw Guide (Single)
            if (showGuide) {
                drawLayer(guideBitmap, 1.0f, 0f, 0f, 0f, 0f, 0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, false, -1, androidx.compose.ui.graphics.BlendMode.SrcOver)
            } else {
                // Draw Layers (Multi)
                val currentLayers = _layers
                currentLayers.forEachIndexed { index, layer ->
                    if (layer.isVisible) {
                        val bitmap = layerBitmaps[layer.id]
                        if (bitmap != null) {
                            val offX = layer.offset.x
                            val offY = layer.offset.y

                            drawLayer(
                                bitmap,
                                layer.scale,
                                layer.rotationX,
                                layer.rotationY,
                                layer.rotationZ,
                                offX,
                                offY,
                                layer.opacity,
                                layer.brightness,
                                layer.colorBalanceR,
                                layer.colorBalanceG,
                                layer.colorBalanceB,
                                layer.id == activeLayerId,
                                index,
                                layer.blendMode
                            )
                        }
                    }
                }
                if (currentLayers.isEmpty() && overlayBitmap != null) {
                    drawLayer(overlayBitmap, scale, rotationX, rotationY, rotationZ, 0f, 0f, opacity, brightness, colorBalanceR, colorBalanceG, colorBalanceB, true, 0, androidx.compose.ui.graphics.BlendMode.SrcOver)
                }
            }
        }
    }

    private fun drawLayer(
        bitmap: Bitmap?,
        scale: Float,
        rotX: Float,
        rotY: Float,
        rotZ: Float,
        offsetX: Float,
        offsetY: Float,
        opacity: Float,
        brightness: Float,
        colorR: Float,
        colorG: Float,
        colorB: Float,
        reportBounds: Boolean,
        layerIndex: Int,
        blendMode: androidx.compose.ui.graphics.BlendMode
    ) {
        if (bitmap == null) return
        val pose = arImagePose ?: return

        System.arraycopy(pose, 0, calculationModelMatrix, 0, 16)
        val modelMtx = calculationModelMatrix

        if (arState == ArState.PLACED) {
            Matrix.rotateM(modelMtx, 0, -90f, 1f, 0f, 0f)
        }

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

        simpleQuadRenderer.draw(
            calculationMvpMatrix, calculationModelViewMatrix,
            bitmap, opacity, brightness, colorR, colorG, colorB,
            if (isDepthSupported) depthTextureId else -1,
            backgroundRenderer.textureId,
            viewportWidth.toFloat(),
            viewportHeight.toFloat(),
            displayToCameraTransform,
            blendMode
        )

        if (reportBounds) {
            calculateAndReportBounds(calculationMvpMatrix)
        }
    }

    private fun calculateAndReportBounds(mvpMtx: FloatArray) {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (i in 0 until 4) {
            val offset = i * 4
            Matrix.multiplyMV(calculationTempVec, 0, mvpMtx, 0, boundsCorners, offset)

            if (calculationTempVec[3] != 0f) {
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

    private fun handleTap(frame: Frame) {
        val tap = tapQueue.poll() ?: return
        val hitResult = frame.hitTest(tap.first, tap.second)
        for (hit in hitResult) {
            val trackable = hit.trackable
            if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                val camFwdX = -viewMtx[2]; val camFwdY = -viewMtx[6]; val camFwdZ = -viewMtx[10]
                val hitPose = hit.hitPose
                hitPose.toMatrix(calculationPoseMatrix, 0)
                val normX = calculationPoseMatrix[4]; val normY = calculationPoseMatrix[5]; val normZ = calculationPoseMatrix[6]
                val dot = camFwdX * normX + camFwdY * normY + camFwdZ * normZ
                if (abs(dot) > 0.7f) {
                    try {
                        activeAnchor?.detach()
                        activeAnchor = hit.createAnchor()
                        arState = ArState.PLACED
                        mainHandler.post { onAnchorCreated() }
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Fatal error creating anchor from tap", e)
                    }
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
            val trackable = hit.trackable
            if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                try {
                    activeAnchor?.detach()
                    activeAnchor = hit.createAnchor()
                    val previousState = arState
                    arState = ArState.PLACED
                    if (previousState != ArState.PLACED) mainHandler.post { onAnchorCreated() }
                    hit.hitPose.toMatrix(calculationPoseMatrix, 0)
                    arImagePose = calculationPoseMatrix.clone()
                    break
                } catch (e: Exception) { Log.e(TAG, "Error creating anchor during pan", e) }
            }
        }
    }

    private fun projectPoseToScreen(modelMtx: FloatArray, viewMtx: FloatArray, projMtx: FloatArray): Pair<Float, Float>? {
        worldPos[0] = modelMtx[12]; worldPos[1] = modelMtx[13]; worldPos[2] = modelMtx[14]; worldPos[3] = 1.0f
        Matrix.multiplyMV(eyePos, 0, viewMtx, 0, worldPos, 0)
        Matrix.multiplyMV(clipPos, 0, projMtx, 0, eyePos, 0)
        if (clipPos[3] == 0f) return null
        val screenX = (clipPos[0] / clipPos[3] + 1f) / 2f * viewportWidth
        val screenY = (1f - clipPos[1] / clipPos[3]) / 2f * viewportHeight
        return Pair(screenX, screenY)
    }

    private fun captureFrameForFingerprint(frame: Frame) {
        try {
            frame.acquireCameraImage().use { image ->
                val width = image.width; val height = image.height
                if (cachedCaptureBitmap == null || cachedCaptureBitmap!!.width != width || cachedCaptureBitmap!!.height != height) {
                    cachedCaptureBitmap?.recycle()
                    cachedCaptureBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                }
                val rawBitmap = cachedCaptureBitmap!!
                yuvToRgbConverter.yuvToRgb(image, rawBitmap)
                val rotation = getRotationDegrees()
                val bitmap = if (rotation != 0f) BitmapUtils.rotateBitmap(rawBitmap, rotation) else rawBitmap.copy(Bitmap.Config.ARGB_8888, true)
                mainHandler.post { onFrameCaptured(bitmap) }
            }
        } catch (e: Exception) { Log.e(TAG, "Capture failed", e) }
    }

    private fun analyzeFrameAsync(frame: Frame) {
        if (isAnalyzing.getAndSet(true)) return
        val image = try { frame.acquireCameraImage() } catch (e: Exception) { isAnalyzing.set(false); return }

        try {
            val rotation = getRotationDegrees()
            val plane = image.planes[0]
            val buffer = plane.buffer
            val width = image.width; val height = image.height
            val rowStride = plane.rowStride
            val requiredSize = width * height
            if (analysisBuffer == null || analysisBuffer!!.size != requiredSize) analysisBuffer = ByteArray(requiredSize)
            val data = analysisBuffer!!
            buffer.rewind()
            if (width == rowStride) buffer.get(data, 0, requiredSize) else {
                for (row in 0 until height) { buffer.position(row * rowStride); buffer.get(data, row * width, width) }
            }
            image.close()

            analysisScope.launch {
                // --- STRICT LOCAL MAT MANAGEMENT ---
                if (!ensureOpenCVLoaded()) { isAnalyzing.set(false); return@launch }
                
                val rawMat = Mat()
                val rotatedMat = Mat()
                val descriptors = Mat()
                val targetDescriptors = Mat()
                val keypoints = MatOfKeyPoint()
                val matches = MatOfDMatch()
                val mask = Mat()
                val orbLocal = ORB.create()
                val matcherLocal = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)

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

                    orbLocal.detectAndCompute(finalMat, mask, keypoints, descriptors)

                    val fingerprint = fingerprintData
                    if (descriptors.rows() > 0 && fingerprint != null && fingerprint.descriptorsData.isNotEmpty()) {
                        // Reconstruct target descriptors Mat locally
                        targetDescriptors.create(fingerprint.descriptorsRows, fingerprint.descriptorsCols, fingerprint.descriptorsType)
                        targetDescriptors.put(0, 0, fingerprint.descriptorsData)

                        matcherLocal.match(descriptors, targetDescriptors, matches)
                        val total = matches.rows()
                        var good = 0
                        if (total > 0) {
                            val count = total * 4
                            val matchData = FloatArray(count)
                            matches.get(0, 0, matchData)
                            for (i in 0 until total) { if (matchData[i * 4 + 3] < 60) good++ }
                        }
                        val ratio = if (originalKeypointCount > 0) good.toFloat() / originalKeypointCount.toFloat() else 0f
                        mainHandler.post { onProgressUpdated((1.0f - ratio).coerceIn(0f, 1f) * 100f, null) }
                    }
                } catch (e: Exception) { Log.e(TAG, "Analysis error", e) } finally {
                    rawMat.release(); rotatedMat.release(); descriptors.release(); targetDescriptors.release()
                    keypoints.release(); matches.release(); mask.release()
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
        } catch (e: Exception) { Log.e(TAG, "Flashlight error", e) } finally { sessionLock.unlock() }
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
                    isDepthSupported = false
                    session!!.configure(config)
                    initialAugmentedImages?.let { images ->
                        if (images.isNotEmpty()) {
                            configJob = CoroutineScope(Dispatchers.Main).launch {
                                val s = session ?: return@launch
                                val db = configureAugmentedImageDatabase(s, images) ?: return@launch
                                sessionLock.lock()
                                try { if (this@ArRenderer.session == s && isSessionResumed) { val c = s.config; c.augmentedImageDatabase = db; s.configure(c) } }
                                catch (e: Exception) { Log.e(TAG, "Failed to configure AugmentedImageDatabase", e) }
                                finally { sessionLock.unlock() }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Resume error (creation)", e) } finally { sessionLock.unlock() }

        resumeJob?.cancel()
        resumeJob = CoroutineScope(Dispatchers.IO).launch {
            sessionLock.lock()
            try { if (session != null && !isSessionResumed) { session!!.resume(); isSessionResumed = true } }
            catch (e: Exception) { Log.e(TAG, "Resume error (session)", e) } finally { sessionLock.unlock() }
        }
    }

    fun onPause() {
        resumeJob?.cancel()
        sessionLock.lock()
        try { configJob?.cancel(); configJob = null; isSessionResumed = false; displayRotationHelper.onPause(); session?.pause() }
        catch (e: Exception) { Log.e(TAG, "Pause error", e) } finally { sessionLock.unlock() }
    }

    fun cleanup() {
        sessionLock.lock()
        try { configJob?.cancel(); configJob = null; analysisScope.cancel(); session?.close()
            cachedCaptureBitmap?.recycle(); cachedCaptureBitmap = null; layerBitmaps.clear() }
        catch (e: Exception) { Log.e(TAG, "Cleanup error", e) } finally { session = null; sessionLock.unlock() }
    }

    fun resetAnchor() {
        sessionLock.lock()
        try { activeAnchor?.detach(); activeAnchor = null; arImagePose = null; arState = ArState.SEARCHING } finally { sessionLock.unlock() }
    }

    fun queueTap(x: Float, y: Float) { tapQueue.offer(Pair(x, y)) }
    fun queuePan(dx: Float, dy: Float) { synchronized(panLock) { pendingPanX += dx; pendingPanY += dy } }

    fun createGeospatialAnchor(lat: Double, lng: Double, alt: Double, headingDegrees: Double) {
        val s = session ?: return
        val earth = s.earth ?: return
        val angleRad = Math.toRadians(-headingDegrees)
        val halfAngle = angleRad / 2.0
        sessionLock.lock()
        try { if (earth.trackingState == TrackingState.TRACKING) {
            activeAnchor?.detach()
            activeAnchor = earth.createAnchor(lat, lng, alt, 0f, Math.sin(halfAngle).toFloat(), 0f, Math.cos(halfAngle).toFloat())
            arState = ArState.PLACED; mainHandler.post { onAnchorCreated() }
        } } catch (e: Exception) { Log.e(TAG, "Failed to create geospatial anchor", e) } finally { sessionLock.unlock() }
    }

    fun updateOverlayImage(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = context.imageLoader.execute(ImageRequest.Builder(context).data(uri).allowHardware(false).build())
                val d = result.drawable
                if (d is android.graphics.drawable.BitmapDrawable) overlayBitmap = d.bitmap
            } catch (e: Exception) { Log.e(TAG, "Overlay update error", e) }
        }
    }

    fun setLayers(newLayers: List<OverlayLayer>) {
        this._layers = newLayers
        var needsLoading = false
        for (l in newLayers) { if (!layerBitmaps.containsKey(l.id)) { needsLoading = true; break } }
        if (!needsLoading && layerBitmaps.size == newLayers.size) return
        CoroutineScope(Dispatchers.IO).launch {
            newLayers.forEach { layer ->
                if (!layerBitmaps.containsKey(layer.id)) {
                    try {
                        val result = context.imageLoader.execute(ImageRequest.Builder(context).data(layer.uri).allowHardware(false).build())
                        val d = result.drawable
                        if (d is android.graphics.drawable.BitmapDrawable) layerBitmaps[layer.id] = d.bitmap
                    } catch (e: Exception) { Log.e(TAG, "Failed to load layer bitmap: ${layer.id}", e) }
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
        init { ensureOpenCVLoaded() }
    }
}
