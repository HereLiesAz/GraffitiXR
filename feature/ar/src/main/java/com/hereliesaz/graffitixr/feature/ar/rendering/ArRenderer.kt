// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/rendering/ArRenderer.kt
package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import com.hereliesaz.graffitixr.common.model.ArScanMode
import com.hereliesaz.graffitixr.common.model.MuralMethod
import com.hereliesaz.graffitixr.common.model.ScanPhase
import com.hereliesaz.graffitixr.common.util.ImageProcessingUtils
import com.hereliesaz.graffitixr.feature.ar.DisplayRotationHelper
import com.hereliesaz.graffitixr.feature.ar.anchor.AnchorOrchestrator
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.withLock

class ArRenderer(
    private val context: Context,
    private val slamManager: SlamManager,
    // Last arg is the camera→point distance (meters) at the tapped pixel, or -1f when unavailable.
    private val onTargetCaptured: (Bitmap, Int, Int, ByteBuffer?, Int, Int, Int, FloatArray?, FloatArray, Int, Float) -> Unit,
    private val onTrackingUpdated: (Boolean, Int, Int, Boolean, Float, Float, Triple<Float, Float, Float>?, Boolean, Boolean, Float, Float, Float) -> Unit,
    private val onLightUpdated: (Float) -> Unit,
    private val onDiag: (String) -> Unit = {},
    // Fired once on the GL thread immediately after the primary anchor is
    // created. The ViewModel uses this to flip ArUiState.isAnchorEstablished,
    // which in turn unlocks the Design rail and advances scanPhase to COMPLETE.
    private val onAnchorEstablished: () -> Unit = {},
    // Fired from the GL thread when ARCore has been stuck not-tracking for a
    // sustained run of frames (true) or recovers (false). MainScreen uses this
    // to drop the GLSurfaceView render mode to WHEN_DIRTY so the saturated
    // main thread can serve input again.
    private val onTrackingLoopStuck: (Boolean) -> Unit = {}
) : GLSurfaceView.Renderer {

    /**
     * When true the render loop early-returns on the next tick instead of
     * driving ARCore. Set by ArViewModel.exitArMode() so that mode-exit is
     * effectively instantaneous from the user's perspective even if the
     * session cleanup coroutine hasn't completed yet.
     */
    @Volatile var isDestroying: Boolean = false

    // Consecutive frames the ARCore camera has reported a non-TRACKING state.
    // When this crosses STUCK_THRESHOLD we fire onTrackingLoopStuck(true) so
    // the host can downgrade GL render mode. We fire (false) on recovery.
    private var consecutiveNotTrackingFrames = 0
    private var trackingLoopStuckReported = false
    private val stuckThresholdFrames = 30

    private val backgroundScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val sessionLock = ReentrantLock()

    var session: Session? = null
        private set

    /**
     * Latest ARCore [Frame] snapshot, refreshed each tick. Read-only for off-thread
     * callers (e.g. UI hit-testing); may be null before the first successful update.
     */
    val latestFrame: AtomicReference<Frame?> = AtomicReference(null)

    private val backgroundRenderer = BackgroundRenderer()
    private val displayRotationHelper = DisplayRotationHelper(context)
    private val overlayRenderer = OverlayRenderer(context)
    private val pointCloudRenderer = PointCloudRenderer()
    private val planeRenderer = PlaneRenderer()
    private val anchorOrchestrator = AnchorOrchestrator()
    private val poseFusion = com.hereliesaz.graffitixr.feature.ar.anchor.PoseFusion()
    // A/B switch for the Sub-project A harness: when false, reproduce the old pre/post-anchor toggle.
    @Volatile var fusionEnabled: Boolean = true
    // Whether the ARCore Depth API is actually enabled this session. Off by default — it starves VIO
    // on this hardware; metric depth comes from triangulation/stereo instead. Set from ArViewModel.
    @Volatile var depthApiEnabled: Boolean = false

    @Volatile var scanMode: ArScanMode = ArScanMode.MURAL
    @Volatile var muralMethod: MuralMethod = MuralMethod.VOXEL_HASH

    // Eval (Sub-project A): null unless dev/eval mode is on. Set from ArViewModel.
    @Volatile var driftCostProbe: com.hereliesaz.graffitixr.feature.ar.eval.DriftCostProbe? = null
    // Scratch holder for the mark-PnP "truth" pose read from the native anchor transform.
    private val truthPoseScratch = FloatArray(16)
    // Visible-confidence threshold above which mark-PnP is treated as truth for eval.
    private val markVisibleConf = 0.5f

    @Volatile var showAnchorBoundary: Boolean = false
    @Volatile var showBorderForConfirmation: Boolean = false
    @Volatile var anchorEstablished: Boolean = false
        set(value) {
            field = value
            if (!value) {
                anchorOrchestrator.clear()
                quadInitialFitApplied = false
            }
        }
    /** When true the SLAM/cloud visualization is suppressed — processing continues but nothing is drawn. */
    @Volatile var hideVisualization: Boolean = false
    /** 36-bit mask of visited 10° yaw sectors. Pushed from `ArUiState.visitedSectorsMask`. */
    @Volatile var visitedSectorsMask: Long = 0L
    /** Current scan phase. Fog is rendered only when AMBIENT and not yet anchored. */
    @Volatile var scanPhase: ScanPhase = ScanPhase.AMBIENT
    var stereoProvider: com.hereliesaz.graffitixr.nativebridge.depth.StereoDepthProvider? = null

    @Volatile private var isFlashlightRequested: Boolean = false

    fun saveCloudPoints(path: String) {
        pointCloudRenderer.saveToFile(path)
    }

    fun scheduleCloudPointsLoad(path: String) {
        pointCloudRenderer.pendingLoadPath = path
    }

    @Volatile private var pendingOverlayBitmap: Bitmap? = null
    @Volatile private var overlayBitmapDirty = false

    // Tracks whether the screen-fit quad extent has been applied for this anchor.
    // Cleared when the anchor is reset; set after the first successful bitmap upload
    // so subsequent re-composites don't snap the user's scale back to the initial fit.
    @Volatile private var quadInitialFitApplied = false
    @Volatile private var lastBitmapW: Int = 0
    @Volatile private var lastBitmapH: Int = 0

    private var frameCount = 0
    private var diagFrameCount = 0
    private var sensorOrientation = 90
    private var isSurfaceCreated = false

    private var lastPoseX = 0f
    private var lastPoseY = 0f
    private var lastPoseZ = 0f

    @Volatile var captureRequested: Boolean = false
    // Normalized (0..1) screen tap to measure distance for on the next capture; consumed on the GL thread.
    @Volatile var pendingCaptureTap: FloatArray? = null
    @Volatile private var surfaceWidth: Int = 0
    @Volatile private var surfaceHeight: Int = 0
    // EMA-smoothed center depth (meters) for the live reticle; raw per-pixel ARCore depth is too noisy.
    @Volatile private var smoothedCenterDepth: Float = -1f
    @Volatile var isCapturingTarget: Boolean = false
    @Volatile var isInPlaneRealignment: Boolean = false
    @Volatile var pendingAnchorEstablishment: Boolean = false

    @Volatile var exportRequested: Boolean = false
    var onExportCaptured: ((Bitmap) -> Unit)? = null

    // Pre-allocated buffers for Surface Mesh updates (32x32 grid)
    private val meshVerticesBuffer = FloatArray(32 * 32 * 3)
    private val meshWeightsBuffer = FloatArray(32 * 32)

    fun attachSession(session: Session?) {
        sessionLock.withLock {
            this.session = session
            if (session != null) {
                displayRotationHelper.onResume()
                if (isSurfaceCreated) {
                    session.setCameraTextureName(backgroundRenderer.textureId)
                }

                // Apply queued flashlight state immediately upon attachment
                applyFlashlightState()

                try {
                    val cameraId = session.cameraConfig.cameraId
                    val manager = context.getSystemService(android.content.Context.CAMERA_SERVICE)
                            as android.hardware.camera2.CameraManager
                    sensorOrientation = manager
                        .getCameraCharacteristics(cameraId)
                        .get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION)
                        ?: 90
                } catch (e: Exception) {
                    sensorOrientation = 90
                }
            } else {
                displayRotationHelper.onPause()
            }
        }
    }

    fun updateOverlayBitmap(bitmap: Bitmap?) {
        pendingOverlayBitmap = bitmap
        overlayBitmapDirty = true
        if (bitmap != null) {
            lastBitmapW = bitmap.width
            lastBitmapH = bitmap.height
        }
    }

    fun updateOverlayExtent(halfW: Float, halfH: Float) {
        // Border marks the detected anchor region (small, derived from depth).
        overlayRenderer.setBorderExtent(halfW, halfH)
        // Image quad is always large so artwork is never spatially confined.
        overlayRenderer.setExtent(OverlayRenderer.QUAD_HALF_EXTENT, OverlayRenderer.QUAD_HALF_EXTENT)
    }

    fun updateFlashlight(isOn: Boolean) {
        isFlashlightRequested = isOn
        applyFlashlightState()
    }

    private fun applyFlashlightState() {
        val activeSession = session ?: return
        try {
            val config = activeSession.config
            val newMode = if (isFlashlightRequested) Config.FlashMode.TORCH else Config.FlashMode.OFF
            if (config.flashMode != newMode) {
                config.flashMode = newMode
                activeSession.configure(config)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to set flash mode via ARCore Config")
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        backgroundRenderer.createOnGlThread(context)

        slamManager.resetGlContext()
        slamManager.initGl()

        overlayRenderer.createOnGlThread()
        pointCloudRenderer.createOnGlThread(context)
        planeRenderer.createOnGlThread(context)
        isSurfaceCreated = true

        sessionLock.withLock {
            session?.setCameraTextureName(backgroundRenderer.textureId)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        surfaceWidth = width
        surfaceHeight = height
        displayRotationHelper.onSurfaceChanged(width, height)
        slamManager.setViewportSize(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        // Mode-exit was requested: stop driving ARCore so teardown is effectively
        // instantaneous and we never touch a session that is being closed.
        if (isDestroying) return
        frameCount++

        sessionLock.withLock {
            val activeSession = session ?: return

            activeSession.setCameraTextureName(backgroundRenderer.textureId)
            displayRotationHelper.updateSessionIfNeeded(activeSession)

            val frame: Frame = try {
                activeSession.update()
            } catch (e: SessionPausedException) {
                return
            } catch (e: Exception) {
                Timber.e(e, "ARCore session update failed")
                return
            }

            latestFrame.set(frame)
            // During the AMBIENT scan, the camera background renders as a newspaper halftone with full
            // colour bleeding in (like ink) as each yaw sector is mapped — the world-mapping indicator.
            val scanActive = !anchorEstablished && scanMode == ArScanMode.MURAL && scanPhase == ScanPhase.AMBIENT
            if (scanActive) backgroundRenderer.updateScanMask(visitedSectorsMask)
            backgroundRenderer.draw(frame, scanActive)

            if (pendingAnchorEstablishment) {
                pendingAnchorEstablishment = false
                try {
                    val camera = frame.camera
                    val viewMat = FloatArray(16)
                    camera.getViewMatrix(viewMat, 0)
                    
                    val hitX = 0.5f; val hitY = 0.5f
                    val hits = frame.hitTest(hitX * context.resources.displayMetrics.widthPixels.toFloat(), hitY * context.resources.displayMetrics.heightPixels.toFloat())
                    
                    var anchorModelMatrix = FloatArray(16)
                    android.opengl.Matrix.setIdentityM(anchorModelMatrix, 0)

                    val fallbackMatrix = FloatArray(16)
                    android.opengl.Matrix.invertM(fallbackMatrix, 0, viewMat, 0)
                    fallbackMatrix[12] += -fallbackMatrix[8] * 2.0f
                    fallbackMatrix[13] += -fallbackMatrix[9] * 2.0f
                    fallbackMatrix[14] += -fallbackMatrix[10] * 2.0f

                    // Prefer a real surface (a wall/plane) over the nearest hit. hits[0] is the closest
                    // result, which is usually a stray feature point in front of the wall — anchoring to
                    // it puts the overlay between the camera and the marks. Pick the first Plane hit
                    // inside its polygon; else a depth/feature point; else the nearest hit.
                    var chosen: com.google.ar.core.HitResult? = null
                    for (h in hits) {
                        val t = h.trackable
                        if (t is com.google.ar.core.Plane && t.isPoseInPolygon(h.hitPose)) { chosen = h; break }
                        if (chosen == null && (t is com.google.ar.core.DepthPoint || t is com.google.ar.core.Point)) chosen = h
                    }
                    if (chosen == null) chosen = hits.firstOrNull()

                    var anchor: com.google.ar.core.Anchor? = null
                    if (chosen != null) {
                        val pose = chosen.hitPose
                        val camPose = camera.pose
                        val dx = pose.tx() - camPose.tx()
                        val dy = pose.ty() - camPose.ty()
                        val dz = pose.tz() - camPose.tz()
                        val dist = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble())
                        if (dist in 0.1..10.0) {
                            pose.toMatrix(anchorModelMatrix, 0) // full surface pose (position + normal)
                            // Anchor to the hit's trackable so ARCore keeps it pinned to the wall as the
                            // artist moves, instead of a free world point at a guessed depth.
                            anchor = chosen.createAnchor()
                        }
                    }
                    if (anchor == null) {
                        anchorModelMatrix = fallbackMatrix
                        anchor = activeSession.createAnchor(
                            com.google.ar.core.Pose(
                                floatArrayOf(anchorModelMatrix[12], anchorModelMatrix[13], anchorModelMatrix[14]),
                                floatArrayOf(0f, 0f, 0f, 1f)
                            )
                        )
                    }

                    slamManager.updateAnchorTransform(anchorModelMatrix)
                    setPrimaryAnchor(anchor!!) // non-null: the fallback branch always creates one
                    anchorEstablished = true
                    hideVisualization = true
                    onAnchorEstablished()

                    Timber.d("Anchor established on GL thread via consensus orchestrator.")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to establish anchor on GL thread")
                }
            }

            GLES30.glDepthMask(true)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthFunc(GLES30.GL_LEQUAL)

            val camera = frame.camera

            val isDualLensHardware = activeSession.cameraConfig.stereoCameraUsage == com.google.ar.core.CameraConfig.StereoCameraUsage.REQUIRE_AND_USE
            val isDualLens = isDualLensHardware || (stereoProvider?.isDualLensActive == true)

            val viewMatrix = FloatArray(16)
            val projMatrix = FloatArray(16)
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f)

            val mappingViewMatrix = FloatArray(16)
            val mappingProjMatrix = FloatArray(16)
            camera.pose.inverse().toMatrix(mappingViewMatrix, 0)

            val intrinsics = camera.imageIntrinsics
            val focalLength = intrinsics.focalLength
            val principalPoint = intrinsics.principalPoint
            val dims = intrinsics.imageDimensions

            mappingProjMatrix[0] = 2.0f * focalLength[0] / dims[0]
            mappingProjMatrix[5] = 2.0f * focalLength[1] / dims[1]
            mappingProjMatrix[8] = 2.0f * principalPoint[0] / dims[0] - 1.0f
            mappingProjMatrix[9] = 1.0f - 2.0f * principalPoint[1] / dims[1]
            mappingProjMatrix[10] = -(100.1f) / (99.9f)
            mappingProjMatrix[11] = -1.0f
            mappingProjMatrix[14] = -(2.0f * 100.0f * 0.1f) / (99.9f)
            mappingProjMatrix[15] = 0.0f

            slamManager.updateCamera(viewMatrix, projMatrix, mappingViewMatrix, mappingProjMatrix, frame.timestamp)

            val lightEstimate = frame.lightEstimate
            if (lightEstimate.state == com.google.ar.core.LightEstimate.State.VALID) {
                onLightUpdated(lightEstimate.pixelIntensity)
            }

            val isTracking = camera.trackingState == TrackingState.TRACKING
            // Re-arm the hard cold-snap whenever ARCore isn't tracking (pocket / screen-off / loss), so
            // the next confident relocalization snaps the overlay back instantly rather than easing in.
            if (!isTracking) poseFusion.markRelocalizing()
            val depthSupported = depthApiEnabled && activeSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)

            val yawRad = kotlin.math.atan2(-viewMatrix[2].toDouble(), -viewMatrix[10].toDouble())
            val yawDeg = Math.toDegrees(yawRad).toFloat()

            // (The world-mapping indicator is now the camera-background "ink develop" reveal, applied
            // in BackgroundRenderer.draw(frame, scanActive) above — no separate overlay here.)

            slamManager.setArCoreTrackingState(isTracking)

            if (anchorEstablished && frameCount % 60 == 0) {
                // Promotion: Check for support anchor candidates
                val candidates = slamManager.getAnchorCandidates(0.95f, 3)
                candidates?.let { data ->
                    for (i in 0 until (data.size / 3)) {
                        val worldPos = com.google.ar.core.Pose(
                            floatArrayOf(data[i*3], data[i*3+1], data[i*3+2]),
                            floatArrayOf(0f,0f,0f,1f)
                        )
                        anchorOrchestrator.addSupportAnchor(activeSession, worldPos)
                    }
                }
            }

            val currentScanMode = scanMode
            slamManager.setArScanMode(currentScanMode.ordinal)
            slamManager.setMuralMethod(muralMethod.ordinal)
            
            // --- Democratic Consensus Transformation + smoothed reloc fusion ---
            // Backbone: ARCore consensus once anchored, else the native cached pose (as before).
            val backbone = FloatArray(16)
            if (anchorEstablished) {
                anchorOrchestrator.getConsensusMatrix(backbone)
            } else {
                System.arraycopy(slamManager.getAnchorTransform(), 0, backbone, 0, 16)
            }
            // Fuse in the corrected mark-PnP snap (smoothed) when anchored; the flag lets the eval
            // harness A/B fusion vs the old toggle. Off → exact previous behavior.
            val anchorMatrix: FloatArray = if (fusionEnabled && anchorEstablished) {
                poseFusion.currentAnchor(
                    backbone = backbone,
                    vCurrent = viewMatrix,
                    reloc = slamManager.getRelocResult(),
                    fpAnchor = slamManager.getFingerprintAnchor(),
                    confGlobal = slamManager.getGlobalConfidenceAvg(),
                )
            } else backbone

            // Throttle UI and distance updates to 15Hz to match SLAM processing frequency and reduce state churn.
            if (frameCount % 4 == 0) {
                driftCostProbe?.let { probe ->
                    val stageMs = slamManager.getStageTimings()
                    // Truth = mark-PnP pose when a confident match exists; getVisibleConfidenceAvg()
                    // gates "marks visible". Reuse the native anchor transform as the PnP-refined pose.
                    val marksVisible = slamManager.getVisibleConfidenceAvg() > markVisibleConf
                    val truth = if (marksVisible) {
                        System.arraycopy(slamManager.getAnchorTransform(), 0, truthPoseScratch, 0, 16)
                        truthPoseScratch
                    } else null
                    probe.onTick(
                        candidatePose = anchorMatrix,
                        truthPose = truth,
                        isTracking = anchorEstablished,
                        stageMs = stageMs,
                        cpuPct = -1f, // CPU% sampled by overlay; -1 here keeps the GL thread cheap
                    )
                }

                backgroundScope.launch {
                    val (count, immutableCount) = if (currentScanMode == ArScanMode.CLOUD_POINTS) {
                        pointCloudRenderer.accumulatedPointCount to 0
                    } else {
                        slamManager.getSplatCount() to slamManager.getImmutableSplatCount()
                    }

                    val visConf = slamManager.getVisibleConfidenceAvg()
                    val globConf = slamManager.getGlobalConfidenceAvg()

                    // Keep last smoothed value across invalid/dropped frames so the readout is stable.
                    var centerDepth = smoothedCenterDepth
                    if (depthApiEnabled) try {
                        frame.acquireDepthImage16Bits().use { depthImage ->
                            val plane = depthImage.planes[0]
                            // Median of a 7x7 patch (radius 3) rejects per-pixel noise/holes; the
                            // single-pixel read made the live distance flicker wildly.
                            val raw = com.hereliesaz.graffitixr.feature.ar.eval.DepthLookup.depthMetersAtPatch(
                                plane.buffer, plane.rowStride, depthImage.width, depthImage.height, 0.5f, 0.5f, radius = 3
                            )
                            if (raw > 0f) {
                                smoothedCenterDepth = if (smoothedCenterDepth <= 0f) raw
                                    else 0.2f * raw + 0.8f * smoothedCenterDepth
                                centerDepth = smoothedCenterDepth
                            }
                        }
                    } catch (e: Exception) { /* ignore */ }

                    var relDir: Triple<Float, Float, Float>? = null
                    val distanceMeters = run {
                        if (!anchorEstablished) return@run -1f
                        val camPose = FloatArray(16)
                        android.opengl.Matrix.invertM(camPose, 0, viewMatrix, 0)
                        val dx = anchorMatrix[12] - camPose[12]
                        val dy = anchorMatrix[13] - camPose[13]
                        val dz = anchorMatrix[14] - camPose[14]
                        val len = kotlin.math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()

                        if (len > 0.01f) {
                            val localX = dx * viewMatrix[0] + dy * viewMatrix[1] + dz * viewMatrix[2]
                            val localY = dx * viewMatrix[4] + dy * viewMatrix[5] + dz * viewMatrix[6]
                            val localZ = dx * viewMatrix[8] + dy * viewMatrix[9] + dz * viewMatrix[10]
                            relDir = Triple(localX / len, localY / len, localZ / len)
                        }

                        val fwdDot = dx * (-viewMatrix[8]) + dy * (-viewMatrix[9]) + dz * (-viewMatrix[10])
                        if (len > 0.01f && fwdDot > 0f) len else -1f
                    }

                    onTrackingUpdated(isTracking, count, immutableCount, depthSupported, yawDeg, distanceMeters, relDir, isDualLens, isDualLensHardware, centerDepth, visConf, globConf)
                }
            }

            if (captureRequested) {
                captureRequested = false
                try {
                    frame.acquireCameraImage().use { image ->
                        val rgbaBuffer = ImageProcessingUtils.convertYuvToRgbaDirect(image)
                        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                        bitmap.copyPixelsFromBuffer(rgbaBuffer)

                        val displayDegrees = displayRotationHelper.getRotation() * 90
                        val rotationNeeded = (sensorOrientation - displayDegrees + 360) % 360

                        var depthBuffer: ByteBuffer? = null
                        var depthWidth = 0
                        var depthHeight = 0
                        var depthStride = 0

                        if (depthApiEnabled) {
                            try {
                                frame.acquireDepthImage16Bits().use { depthImage ->
                                    val plane = depthImage.planes[0]
                                    val buf = ByteBuffer.allocateDirect(plane.buffer.remaining())
                                    buf.put(plane.buffer)
                                    buf.rewind()
                                    depthBuffer = buf
                                    depthWidth = depthImage.width
                                    depthHeight = depthImage.height
                                    depthStride = plane.rowStride
                                }
                            } catch (e: Exception) {
                                Timber.w(e, "Failed to acquire depth for capture")
                            }
                        }

                        val intrArr = floatArrayOf(
                            intrinsics.focalLength[0], intrinsics.focalLength[1],
                            intrinsics.principalPoint[0], intrinsics.principalPoint[1]
                        )

                        // Camera→point distance at the tapped pixel (Sub-project C). Map the tap from
                        // view pixels to the depth image via ARCore's rotation/crop-aware transform,
                        // then read the depth buffer. A confident tap also becomes a fusion support
                        // anchor (done here on the GL thread, where frame + activeSession are valid).
                        var tapDistanceMeters = -1f
                        val tap = pendingCaptureTap
                        pendingCaptureTap = null
                        val capturedDepth = depthBuffer
                        if (tap != null && capturedDepth != null && depthWidth > 0 && surfaceWidth > 0) {
                            try {
                                val viewPx = floatArrayOf(tap[0] * surfaceWidth, tap[1] * surfaceHeight)
                                val imgNorm = FloatArray(2)
                                frame.transformCoordinates2d(
                                    com.google.ar.core.Coordinates2d.VIEW, viewPx,
                                    com.google.ar.core.Coordinates2d.IMAGE_NORMALIZED, imgNorm
                                )
                                tapDistanceMeters = com.hereliesaz.graffitixr.feature.ar.eval.DepthLookup
                                    .depthMetersAtPatch(capturedDepth, depthStride, depthWidth, depthHeight, imgNorm[0], imgNorm[1], radius = 3)
                                if (tapDistanceMeters > 0f && anchorEstablished) {
                                    frame.hitTest(viewPx[0], viewPx[1]).firstOrNull()?.let {
                                        anchorOrchestrator.addSupportAnchor(activeSession, it.hitPose)
                                    }
                                }
                            } catch (e: Exception) {
                                Timber.w(e, "Tap depth/support-anchor lookup failed")
                            }
                        }

                        onTargetCaptured(
                            bitmap, image.width, image.height,
                            depthBuffer,
                            depthWidth, depthHeight, depthStride,
                            intrArr, mappingViewMatrix.copyOf(),
                            rotationNeeded, tapDistanceMeters
                        )
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to capture target frame")
                }
            }

            if (exportRequested) {
                exportRequested = false
                try {
                    frame.acquireCameraImage().use { image ->
                        val rgbaBuffer = ImageProcessingUtils.convertYuvToRgbaDirect(image)
                        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                        bitmap.copyPixelsFromBuffer(rgbaBuffer)

                        val displayDegrees = displayRotationHelper.getRotation() * 90
                        val rotationNeeded = (sensorOrientation - displayDegrees + 360) % 360

                        val rotatedBitmap = if (rotationNeeded != 0) {
                            val matrix = android.graphics.Matrix()
                            matrix.postRotate(rotationNeeded.toFloat())
                            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                                bitmap.recycle()
                            }
                        } else {
                            bitmap
                        }

                        onExportCaptured?.invoke(rotatedBitmap)
                        onExportCaptured = null
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to capture export frame")
                }
            }

            // Throttle frame feeding to 15Hz (every 4 frames at 60Hz) to halve processing-related power draw.
            // When tracking is stable and the device is stationary, we could throttle even further.
            // ── Frame Data Pipeline (Throttle to 20Hz or 2Hz for Battery Efficiency) ──
            val throttleRate = if (anchorEstablished) 30 else 3 // 2Hz vs 20Hz
            if (isTracking && frameCount % throttleRate == 0) {
                // Calculate device motion (linear and angular velocity) for deblurring
                val currentPose = camera.displayOrientedPose
                if (frameCount > 0) {
                    // Simple delta-based velocity estimation
                    // In a production app, we'd use raw IMU high-frequency data.
                    val dt = 1.0f / 20.0f // Approx 20Hz throttle
                    val linVel = floatArrayOf(
                        (currentPose.tx() - lastPoseX) / dt,
                        (currentPose.ty() - lastPoseY) / dt,
                        (currentPose.tz() - lastPoseZ) / dt
                    )
                    // Angular velocity approx from quaternion delta
                    val angVel = floatArrayOf(0f, 0f, 0f) // Stub for now
                    slamManager.updateDeviceMotion(angVel, linVel)
                }
                lastPoseX = currentPose.tx(); lastPoseY = currentPose.ty(); lastPoseZ = currentPose.tz()

                // Calculate rotation code to align sensor-native data with display orientation
                val displayRotation = displayRotationHelper.getRotation()
                val cvRotateCode = when ((sensorOrientation - displayRotation * 90 + 360) % 360) {
                    90 -> 0 // cv::ROTATE_90_CLOCKWISE
                    180 -> 1 // cv::ROTATE_180
                    270 -> 2 // cv::ROTATE_90_COUNTERCLOCKWISE
                    else -> -1
                }

                try {
                    frame.acquireCameraImage().use { image ->
                        val planes = image.planes
                        slamManager.feedYuvFrame(
                            planes[0].buffer, planes[1].buffer, planes[2].buffer,
                            image.width, image.height,
                            planes[0].rowStride, planes[1].rowStride, planes[1].pixelStride,
                            frame.timestamp,
                            cvRotateCode
                        )
                        // Feed temporal stereo ONLY when mapping AND if hardware stereo isn't active
                        if (!anchorEstablished && !isDualLensHardware) {
                            stereoProvider?.submitFrame(planes[0].buffer, image.width, image.height, frame.timestamp)
                        }
                    }
                } catch (e: com.google.ar.core.exceptions.NotYetAvailableException) {
                    // Normal on first frames
                } catch (e: Exception) {
                    Timber.w(e, "Failed to feed YUV frame")
                }

                // 1. Point Cloud acquisition (only when scanning in CLOUD_POINTS mode)
                if (!anchorEstablished) {
                    try {
                        frame.acquirePointCloud().use { pointCloud ->
                            if (currentScanMode == ArScanMode.CLOUD_POINTS) {
                                pointCloudRenderer.update(pointCloud)
                            }
                            
                            // Feed sparse points to Gaussian engine for seeding
                            val pts = FloatArray(pointCloud.points.remaining())
                            pointCloud.points.get(pts)
                            slamManager.feedPointCloud(pts)
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to acquire point cloud")
                    }
                }

                // 2. Depth acquisition (STOP processing depth once target is baked)
                if (depthSupported && !anchorEstablished) {
                    try {
                        frame.acquireDepthImage16Bits().use { depthImage ->
                            val depthPlane = depthImage.planes[0]
                            val intrArr = floatArrayOf(
                                intrinsics.focalLength[0], intrinsics.focalLength[1],
                                intrinsics.principalPoint[0], intrinsics.principalPoint[1]
                            )
                            val cpuDim = intrinsics.imageDimensions

                            slamManager.feedArCoreDepth(
                                depthPlane.buffer,
                                depthImage.width, depthImage.height,
                                depthPlane.rowStride,
                                intrArr, cpuDim[0], cpuDim[1],
                                cvRotateCode,
                                if (isDualLensHardware) 0.9f else 0.5f
                            )
                        }
                    } catch (e: com.google.ar.core.exceptions.NotYetAvailableException) {
                        // Normal on first frames
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to feed depth frame")
                    }
                }
            }

            // Continuous wall-depth refinement: keep the overlay flush with the ARCore plane estimate.
            // Runs at ~1 Hz (every 30 frames) to amortise getAllTrackables() overhead.
            // Only runs BEFORE the anchor is established or during manual realignment to
            // prevent the image from following the camera gaze once locked to a target.
            if ((!anchorEstablished || isInPlaneRealignment) && frameCount % 30 == 0) {
                try {
                    refineAnchorFromBestPlane(activeSession, viewMatrix)
                } catch (e: Exception) {
                    // Non-fatal: skip this refinement cycle
                }
            }

            // Depth-based fallback: sample centre-screen depth every 10 frames
            if ((!anchorEstablished || isInPlaneRealignment) && depthSupported && frameCount % 10 == 0) {
                try {
                    frame.acquireDepthImage16Bits().use { depthImage ->
                        val plane = depthImage.planes[0]
                        val cx = depthImage.width / 2
                        val cy = depthImage.height / 2
                        val stride = plane.rowStride
                        val byteOffset = cy * stride + cx * 2
                        if (byteOffset + 2 <= plane.buffer.limit()) {
                            val rawVal = plane.buffer.getShort(byteOffset).toInt() and 0xFFFF
                            val depthMm = rawVal and 0x1FFF
                            if (depthMm in 100..15000) {   // 10 cm – 15 m
                                val depthM = depthMm / 1000f
                                val cameraMat = FloatArray(16)
                                android.opengl.Matrix.invertM(cameraMat, 0, viewMatrix, 0)
                                val hitX = cameraMat[12] + (-cameraMat[8]) * depthM
                                val hitY = cameraMat[13] + (-cameraMat[9]) * depthM
                                val hitZ = cameraMat[14] + (-cameraMat[10]) * depthM
                                val nx = -cameraMat[8]; val ny = -cameraMat[9]; val nz = -cameraMat[10]
                                var xx = 0f * nz - 1f * ny
                                var xy = 1f * nx - 0f * nz
                                var xz = 0f * ny - 0f * nx
                                val xLen = kotlin.math.sqrt((xx * xx + xy * xy + xz * xz).toDouble()).toFloat()
                                if (xLen > 0.0001f) {
                                    xx /= xLen; xy /= xLen; xz /= xLen
                                    val yx = ny * xz - nz * xy; val yy = nz * xx - nx * xz; val yz = nx * xy - ny * xx
                                    val depthAnchor = FloatArray(16)
                                    android.opengl.Matrix.setIdentityM(depthAnchor, 0)
                                    depthAnchor[0] = xx; depthAnchor[1] = xy; depthAnchor[2] = xz
                                    depthAnchor[4] = yx; depthAnchor[5] = yy; depthAnchor[6] = yz
                                    depthAnchor[8] = nx; depthAnchor[9] = ny; depthAnchor[10] = nz
                                    depthAnchor[12] = hitX; depthAnchor[13] = hitY; depthAnchor[14] = hitZ
                                    slamManager.updateAnchorTransform(depthAnchor)
                                }
                            }
                        }
                    }
                } catch (_: com.google.ar.core.exceptions.NotYetAvailableException) {
                } catch (e: Exception) { /* Non-fatal */ }
            }

            // During scanning we no longer draw the splat cloud / point cloud / plane grid — the
            // newspaper-halftone "ink develop" reveal on the camera background is the mapping indicator.
            // (Mapping/localization still runs; this only suppressed the busy 3D overlays.)

            if (overlayBitmapDirty) {
                overlayBitmapDirty = false
                val bmp = pendingOverlayBitmap
                if (bmp != null) overlayRenderer.updateTexture(bmp) else overlayRenderer.clearTexture()
            }

            // Constrain the textured quad's initial size so it lands within the
            // visible screen frustum at the anchor's distance. Applied once per
            // anchor session — the user can pinch/scale later without being
            // snapped back to the fit.
            if (!quadInitialFitApplied &&
                anchorEstablished &&
                overlayRenderer.hasTexture &&
                lastBitmapW > 0 && lastBitmapH > 0
            ) {
                val camWorld = FloatArray(16)
                android.opengl.Matrix.invertM(camWorld, 0, viewMatrix, 0)
                val ddx = anchorMatrix[12] - camWorld[12]
                val ddy = anchorMatrix[13] - camWorld[13]
                val ddz = anchorMatrix[14] - camWorld[14]
                val dist = kotlin.math.sqrt((ddx * ddx + ddy * ddy + ddz * ddz).toDouble())
                    .toFloat().coerceAtLeast(0.5f)

                // For a standard GL perspective matrix:
                //   projMatrix[0] = 1 / (aspect * tan(fovx/2))
                //   projMatrix[5] = 1 / tan(fovy/2)
                // World half-extent at distance d that just spans the screen:
                //   halfScreenW = d / projMatrix[0],   halfScreenH = d / projMatrix[5]
                val margin = 0.9f
                val halfScreenW = if (projMatrix[0] != 0f) dist / projMatrix[0] * margin else dist * margin
                val halfScreenH = if (projMatrix[5] != 0f) dist / projMatrix[5] * margin else dist * margin

                val bmpAspect = lastBitmapW.toFloat() / lastBitmapH.toFloat()
                val halfWFromH = halfScreenH * bmpAspect
                val halfW: Float
                val halfH: Float
                if (halfWFromH <= halfScreenW) {
                    halfW = halfWFromH
                    halfH = halfScreenH
                } else {
                    halfW = halfScreenW
                    halfH = halfScreenW / bmpAspect
                }

                overlayRenderer.setExtent(halfW, halfH)
                quadInitialFitApplied = true
            }

            val hasMeshData = if (scanMode == ArScanMode.MURAL && muralMethod == MuralMethod.SURFACE_MESH) {
                slamManager.getPersistentMesh(meshVerticesBuffer, meshWeightsBuffer)
                true
            } else false

            overlayRenderer.draw(viewMatrix, projMatrix, anchorMatrix, 
                if (hasMeshData) meshVerticesBuffer else null,
                if (hasMeshData) meshWeightsBuffer else null
            )

            val showBorder = !anchorEstablished && !isCapturingTarget &&
                (showAnchorBoundary || (showBorderForConfirmation && !overlayRenderer.hasTexture))
            if (showBorder) {
                overlayRenderer.drawAnchorBorder(viewMatrix, projMatrix, anchorMatrix)
            }
        }
    }

    fun setPrimaryAnchor(anchor: com.google.ar.core.Anchor) {
        anchorOrchestrator.setInitialAnchor(anchor)
    }

    /**
     * Continuously refines the overlay anchor by finding the best VERTICAL ARCore plane
     * in the camera's forward direction and updating the SLAM anchor transform to match it.
     * Called at ~1 Hz from onDrawFrame to keep the overlay flush with the wall as ARCore
     * refines its plane estimates over time.
     */
    private fun refineAnchorFromBestPlane(
        session: Session,
        viewMatrix: FloatArray
    ) {
        // Extract camera world position and forward vector from the view matrix
        val cameraMat = FloatArray(16)
        android.opengl.Matrix.invertM(cameraMat, 0, viewMatrix, 0)
        val camX = cameraMat[12]; val camY = cameraMat[13]; val camZ = cameraMat[14]
        val fwdX = -cameraMat[8]; val fwdY = -cameraMat[9]; val fwdZ = -cameraMat[10]

        // Find the plane most directly ahead of the camera (unbiased search)
        val planes = session.getAllTrackables(com.google.ar.core.Plane::class.java)
        var bestPlane: com.google.ar.core.Plane? = null
        var maxDot = 0.4f

        for (plane in planes) {
            if (plane.trackingState != TrackingState.TRACKING) continue
            
            val pose = plane.centerPose
            val dx = pose.tx() - camX
            val dy = pose.ty() - camY
            val dz = pose.tz() - camZ
            val len = kotlin.math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
            if (len < 0.3f || len > 15f) continue
            
            val dot = (dx * fwdX + dy * fwdY + dz * fwdZ) / len
            if (dot > maxDot) { 
                maxDot = dot
                bestPlane = plane 
            }
        }

        val plane = bestPlane ?: return

        val planeMatrix = FloatArray(16)
        plane.centerPose.toMatrix(planeMatrix, 0)
        val nx = planeMatrix[4]; val ny = planeMatrix[5]; val nz = planeMatrix[6]  // plane normal (Y col)

        // Ray–plane intersection: where does the camera's forward ray hit this plane?
        val nDotD = nx * fwdX + ny * fwdY + nz * fwdZ
        if (kotlin.math.abs(nDotD) < 0.0001f) return   // Ray parallel to plane
        val t = ((planeMatrix[12] - camX) * nx +
                 (planeMatrix[13] - camY) * ny +
                 (planeMatrix[14] - camZ) * nz) / nDotD
        if (t < 0.1f) return   // Intersection behind camera

        val hitX = camX + fwdX * t
        val hitY = camY + fwdY * t
        val hitZ = camZ + fwdZ * t

        // Build an orthonormal anchor frame: Z = plane normal, X = horizontal, Y = up
        val zx = nx; val zy = ny; val zz = nz
        var refX = 0f; var refY = 1f; var refZ = 0f
        if (kotlin.math.abs(zy) > 0.9f) { refX = 1f; refY = 0f; refZ = 0f }
        
        var xx = refY * zz - refZ * zy
        var xy = refZ * zx - refX * zz
        var xz = refX * zy - refY * zx
        val xLen = kotlin.math.sqrt((xx * xx + xy * xy + xz * xz).toDouble()).toFloat()
        if (xLen < 0.0001f) return   // Degenerate
        xx /= xLen; xy /= xLen; xz /= xLen
        val yx = zy * xz - zz * xy   // Y = Z × X
        val yy = zz * xx - zx * xz
        val yz = zx * xy - zy * xx

        val anchorMat = FloatArray(16)
        android.opengl.Matrix.setIdentityM(anchorMat, 0)
        anchorMat[0] = xx;   anchorMat[1] = xy;   anchorMat[2] = xz
        anchorMat[4] = yx;   anchorMat[5] = yy;   anchorMat[6] = yz
        anchorMat[8] = zx;   anchorMat[9] = zy;   anchorMat[10] = zz
        anchorMat[12] = hitX; anchorMat[13] = hitY; anchorMat[14] = hitZ

        slamManager.updateAnchorTransform(anchorMat)
    }

    /**
     * Deletes all GL objects owned by the sub-renderers. MUST be invoked on the GL
     * thread (e.g. via `GLSurfaceView.queueEvent`) while the EGL context is still
     * alive — never directly from [destroy], which runs off the GL thread.
     */
    fun releaseGlResources() {
        backgroundRenderer.release()
        overlayRenderer.release()
        pointCloudRenderer.release()
        planeRenderer.release()
    }

    /**
     * Non-GL teardown: stops the render loop, cancels the background coroutine
     * scope (previously never cancelled — a coroutine leak), detaches the session,
     * and drops retained references. Safe to call from any thread. GL objects are
     * freed separately via [releaseGlResources] on the GL thread.
     */
    fun destroy() {
        isDestroying = true
        backgroundScope.cancel("Renderer detached and destroyed.")
        sessionLock.withLock {
            session = null
        }
        anchorOrchestrator.clear()
        pendingOverlayBitmap = null
        latestFrame.set(null)
    }
}