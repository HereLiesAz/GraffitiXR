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
import com.hereliesaz.graffitixr.common.model.ScanPhase
import com.hereliesaz.graffitixr.nativebridge.YuvConverter
import com.hereliesaz.graffitixr.feature.ar.AnchorLockTracker
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.withLock

/** Result of [ArRenderer.requestResume]/[ArRenderer.requestPause]. */
sealed class SessionLifecycleOutcome {
    /** Session.resume()/pause() ran to completion. */
    object Applied : SessionLifecycleOutcome()
    /** No live session to act on (never attached, or detached) — not an error. */
    object NoSession : SessionLifecycleOutcome()
    /** The GL thread held [ArRenderer]'s session lock (wedged inside onDrawFrame) past the timeout. */
    object LockTimeout : SessionLifecycleOutcome()
    /** ARCore itself rejected the call (e.g. CameraNotAvailableException). */
    data class Failed(val error: Exception) : SessionLifecycleOutcome()
}

class ArRenderer(
    private val context: Context,
    private val slamManager: SlamManager,
    // Last arg is the camera→point distance (meters) at the tapped pixel, or -1f when unavailable.
    private val onTargetCaptured: (Bitmap, Int, Int, ByteBuffer?, Int, Int, Int, FloatArray?, FloatArray, Int, Float, FloatArray?, com.hereliesaz.graffitixr.common.model.CaptureEnvironment) -> Unit,
    private val onTrackingUpdated: (Boolean, Int, Boolean, Float, Float, Triple<Float, Float, Float>?, Boolean, Float) -> Unit,
    private val onLightUpdated: (Float) -> Unit,
    private val onDiag: (String) -> Unit = {},
    // Fired once on the GL thread immediately after the primary anchor is
    // created. The ViewModel uses this to flip ArUiState.isAnchorEstablished,
    // which in turn unlocks the Design rail and advances scanPhase to COMPLETE.
    private val onAnchorEstablished: () -> Unit = {},
    // Fired once on the GL thread the first time a tracking plane is found. The
    // ViewModel flips ArUiState.planeDetected so first-run onboarding can drop
    // the "move your device" guidance once a surface exists.
    private val onPlaneDetected: () -> Unit = {},
    // First-run walkthrough: fires once when the fused pose has held one spot long
    // enough to place the user's artwork. No-op outside the walkthrough's detect
    // phase (doodleLockActive false).
    private val onDoodleLocked: () -> Unit = {},
    // Fired from the GL thread when a torch request was rejected by ARCore (no flash unit, or a
    // camera config that can't drive one), so the UI can drop the toggle instead of latching a
    // light that never came on.
    private val onFlashlightUnavailable: () -> Unit = {},
    // Fired from the GL thread when the artwork's footprint appears or changes size — the artist
    // placed a design, or pinched one. IMPLEMENTATION.md 2.2/2.4: this is where the fingerprint
    // actually gets partitioned, because target creation is what establishes the anchor the artwork
    // sits on, so a capture has no footprint to partition against.
    //
    // Edge-triggered on the EFFECTIVE (scale-included) half-extents: the renderer knows when the
    // number moved and the ViewModel does not, so filtering here costs one float compare a frame and
    // saves a listener that would otherwise have to poll. It is a FILTER, not a debounce — a live
    // pinch still fires every frame, and collapsing that is the consumer's job.
    private val onDesignFootprintChanged:
        (com.hereliesaz.graffitixr.feature.ar.anchor.FingerprintPartition.DesignFootprint) -> Unit = {},
) : GLSurfaceView.Renderer {

    /**
     * When true the render loop early-returns on the next tick instead of
     * driving ARCore. Set by ArViewModel.exitArMode() so that mode-exit is
     * effectively instantaneous from the user's perspective even if the
     * session cleanup coroutine hasn't completed yet.
     */
    @Volatile var isDestroying: Boolean = false

    private val backgroundScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val sessionLock = ReentrantLock()

    /**
     * The live ARCore session, or null when detached. @Volatile because [destroy] may null this
     * WITHOUT holding [sessionLock] (when the GL thread is wedged inside [onDrawFrame] holding the
     * lock, blocking on it would freeze the caller — see [destroy]). [onDrawFrame] reads it exactly
     * once into a local under the lock, so a concurrent null is observed cleanly on the next frame.
     */
    @Volatile var session: Session? = null
        private set

    /**
     * Latest ARCore [Frame] snapshot, refreshed each tick. Read-only for off-thread
     * callers (e.g. UI hit-testing); may be null before the first successful update.
     */
    /**
     * Latest ARCore frame snapshot. WARNING: do NOT call Session/Frame methods on this from off
     * the GL thread — ARCore is not thread-safe and a hitTest here racing update() corrupts
     * native state. For hit tests, use [requestHitTest], which runs on the GL thread under
     * [sessionLock]. [AtomicReference] only makes the REFERENCE itself safe to read cross-thread;
     * it does not make the [Frame] object it points to safe to call methods on — see
     * [latestFrameTimestampNs] for the one piece of it ([Frame.getTimestamp]) that is meant to be
     * read off-thread.
     */
    val latestFrame: AtomicReference<Frame?> = AtomicReference(null)

    /**
     * Timestamp (ns) of the most recently processed ARCore frame, or 0 if none has arrived yet —
     * a plain `@Volatile Long` cached by the GL thread alongside [latestFrame], specifically so
     * off-thread callers (e.g. [com.hereliesaz.graffitixr.feature.ar.lastArFrameTimestampNs],
     * polled ~1 Hz from a Compose coroutine to report camera-feeding health) never need to touch
     * the [Frame] object itself. Reading `latestFrame.get()?.timestamp` directly used to call
     * [Frame.getTimestamp] — a native ARCore call — from whatever thread the poller ran on, which
     * is exactly what the warning on [latestFrame] says not to do: the AtomicReference makes the
     * REFERENCE safe to hand across threads, not the native call inside it. This field is written
     * only from the GL thread (same place [latestFrame] is set) and is a plain value type, so
     * reading it off-thread touches no ARCore native state at all.
     */
    @Volatile var latestFrameTimestampNs: Long = 0L
        private set

    private class PendingHitTest(
        val x: Float,
        val y: Float,
        val result: kotlinx.coroutines.CompletableDeferred<FloatArray?>
    )
    private val hitTestQueue = java.util.concurrent.ConcurrentLinkedQueue<PendingHitTest>()

    fun requestHitTest(x: Float, y: Float): kotlinx.coroutines.Deferred<FloatArray?> {
        val d = kotlinx.coroutines.CompletableDeferred<FloatArray?>()
        if (isDestroying || session == null) {
            d.complete(null)
            return d
        }
        hitTestQueue.add(PendingHitTest(x, y, d))
        return d
    }

    private fun drainHitTestQueue(frame: Frame) {
        while (true) {
            val req = hitTestQueue.poll() ?: break
            val translation = try {
                selectHitTestTranslation(frame, req.x, req.y)
            } catch (e: Exception) {
                Timber.w(e, "queued hitTest failed")
                null
            }
            req.result.complete(translation)
        }
    }

    private fun selectHitTestTranslation(frame: Frame, x: Float, y: Float): FloatArray? {
        val hits = frame.hitTest(x, y)
        var chosen: com.google.ar.core.HitResult? = null
        for (h in hits) {
            val t = h.trackable
            if (t is com.google.ar.core.Plane && t.isPoseInPolygon(h.hitPose)) { chosen = h; break }
            if (chosen == null && (t is com.google.ar.core.DepthPoint || t is com.google.ar.core.Point)) chosen = h
        }
        if (chosen == null) chosen = hits.firstOrNull()
        val hit = chosen ?: return null
        val pose = hit.hitPose
        val camPose = frame.camera.pose
        val dx = pose.tx() - camPose.tx()
        val dy = pose.ty() - camPose.ty()
        val dz = pose.tz() - camPose.tz()
        val dist = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble())
        if (dist !in 0.1..10.0) return null
        return pose.translation
    }

    private fun failPendingHitTests() {
        while (true) {
            val req = hitTestQueue.poll() ?: break
            req.result.complete(null)
        }
    }

    private val backgroundRenderer = BackgroundRenderer()
    private val displayRotationHelper = DisplayRotationHelper(context)
    private val overlayRenderer = OverlayRenderer(context)
    private val pointCloudRenderer = PointCloudRenderer()
    private val planeRenderer = PlaneRenderer()
    private val arDebugRenderer = ArDebugRenderer()
    @Volatile var showFeaturePoints: Boolean = false
    @Volatile var showPlaneGrids: Boolean = true
    @Volatile var showPoints: Boolean = true
    private val perceptionFbo = PerceptionFbo()
    @Volatile var systemThrottle: Boolean = false
    @Volatile var lagThrottleEnabled: Boolean = true
    private var lastPerceptionRefreshMs = 0L
    private val lastPerceptionView = FloatArray(16)
    private var havePerceptionCache = false
    private var lastPerceptionPointCount = -1
    private var perceptionRefreshAvgMs = 0f
    @Volatile var adaptiveRateEnabled: Boolean = true
    @Volatile var idleRateCeilingFps: Int = 12
    @Volatile var activeRateCeilingFps: Int = 0
    @Volatile var gestureInProgress: Boolean = false
    @Volatile var isIdle: Boolean = false
        private set
    private val idlePose = FloatArray(16)
    private var haveIdlePose = false
    private var noMotionSinceMs = 0L
    private var resumeHoldUntilMs = 0L
    private var lastHeavyWorkMs = 0L

    private val anchorOrchestrator = AnchorOrchestrator()
    private val poseFusion = com.hereliesaz.graffitixr.feature.ar.anchor.PoseFusion()
    @Volatile var fusionEnabled: Boolean = false
    @Volatile var depthApiEnabled: Boolean = false
    @Volatile var ambientScanEnabled: Boolean = true
    @Volatile var driftCostProbe: com.hereliesaz.graffitixr.feature.ar.eval.DriftCostProbe? = null
    @Volatile var fusionSkipReason: com.hereliesaz.graffitixr.common.model.FusionState? = null

    fun primaryAnchorDriftMeters(): Float =
        withLockedSession { anchorOrchestrator.primaryAnchorDriftMeters() } ?: -1f

    fun activeAnchorCount(): Int = withLockedSession { anchorOrchestrator.getActiveAnchorCount() } ?: 0

    fun fusionDiagnostics(): com.hereliesaz.graffitixr.common.model.FusionDiagnostics {
        val skip = fusionSkipReason
        return if (skip != null) {
            com.hereliesaz.graffitixr.common.model.FusionDiagnostics(state = skip)
        } else {
            poseFusion.diagnostics()
        }
    }

    @Volatile var attitudeSampler: (() -> com.hereliesaz.graffitixr.common.model.DeviceAttitude?)? = null
    @Volatile var locationSampler: (() -> com.hereliesaz.graffitixr.common.model.LocationFix?)? = null
    private val truthPoseScratch = FloatArray(16)
    private val MIN_TRUTH_INLIERS = 6
    @Volatile var showAnchorBoundary: Boolean = false
    @Volatile var anchorEstablished: Boolean = false
        set(value) {
            field = value
            if (!value) {
                anchorOrchestrator.clear()
                quadInitialFitApplied = false
                hideVisualization = false
            }
        }
    @Volatile var hideVisualization: Boolean = false
    @Volatile var visitedSectorsMask: Long = 0L
    @Volatile var scanPhase: ScanPhase = ScanPhase.AMBIENT
    @Volatile private var isFlashlightRequested: Boolean = false
    @Volatile private var flashDirty: Boolean = false
    private var flashUnsupportedReported: Boolean = false
    val mappedPointCount: Int get() = pointCloudRenderer.accumulatedPointCount
    private var cloudAnchor: com.google.ar.core.Anchor? = null
    private val cloudAnchorModelScratch = FloatArray(16)
    private val worldToCloudAnchorScratch = FloatArray(16)

    private fun checkCloudAnchorLiveness() {
        val existing = cloudAnchor ?: return
        if (existing.trackingState != TrackingState.TRACKING) {
            try { existing.detach() } catch (_: Exception) { }
            cloudAnchor = null
            pointCloudRenderer.clear()
        }
    }

    private fun cloudAnchorModel(session: Session, camera: com.google.ar.core.Camera): FloatArray? {
        if (camera.trackingState != TrackingState.TRACKING) return null
        checkCloudAnchorLiveness()
        val anchor = cloudAnchor ?: try {
            session.createAnchor(camera.pose).also { cloudAnchor = it }
        } catch (e: Exception) {
            Timber.w(e, "cloud anchor creation failed")
            return null
        }
        anchor.pose.toMatrix(cloudAnchorModelScratch, 0)
        return cloudAnchorModelScratch
    }

    @Volatile private var pendingOverlayBitmap: Bitmap? = null
    @Volatile private var overlayBitmapDirty = false
    @Volatile private var quadInitialFitApplied = false
    @Volatile private var lastBitmapW: Int = 0
    @Volatile private var lastBitmapH: Int = 0
    private var frameCount = 0
    private var camStreamReported = false
    private var camStallWarned = false
    private var planeDetectedReported = false
    private val anchorLockTracker = AnchorLockTracker()
    private var doodleLockReported = false
    private var doodleAutoAnchorRequested = false
    @Volatile var doodleWallPlane: FloatArray? = null
        private set
    @Volatile var doodleLockActive: Boolean = false
        set(value) {
            if (value && !field) {
                doodleLockReported = false
                doodleAutoAnchorRequested = false
                doodleWallPlane = null
                anchorLockTracker.reset()
            }
            field = value
        }
    @Volatile private var lastTickMs = 0L
    @Volatile private var lastStep = "init"
    @Volatile private var stallReported = false
    @Volatile private var camWaitStartMs = 0L
    @Volatile private var cameraNotFeedingReported = false
    var onCameraNotFeeding: (() -> Unit)? = null
    private var watchdog: Thread? = null
    @Volatile private var sensorOrientation = 90
    @Volatile private var lastCaptureRotationNeededDeg =
        com.hereliesaz.graffitixr.feature.ar.eval.EvalSampleLog.NOT_SAMPLED
    private var isSurfaceCreated = false
    private var lastPoseX = 0f
    private var lastPoseY = 0f
    private var lastPoseZ = 0f
    private var lastMotionSampleNs = 0L
    private var lastQuat: FloatArray? = null
    @Volatile var captureRequested: Boolean = false
    @Volatile var pendingCaptureTap: FloatArray? = null
    @Volatile private var surfaceWidth: Int = 0
    @Volatile private var surfaceHeight: Int = 0
    @Volatile private var smoothedCenterDepth: Float = -1f
    @Volatile var isCapturingTarget: Boolean = false
    @Volatile var isInPlaneRealignment: Boolean = false
    @Volatile var pendingAnchorEstablishment: Boolean = false
    private val anchorSurfaceNormal = FloatArray(3)
    private var overlayRotationCorrectionPending: Boolean = false
    private var overlayRotationCorrectionRetryFrames: Int = 0
    private val overlayRotationCorrectionUpSnapshot = FloatArray(3)
    private val overlayRotationCorrection = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )
    private val overlayTargetBasisScratch = FloatArray(16)
    private val overlayRotScratch = FloatArray(16)
    private val overlayRotScratch2 = FloatArray(16)
    private val overlayRotationCorrectionCandidate = FloatArray(16)
    @Volatile var exportRequested: Boolean = false
    var onExportCaptured: ((Bitmap) -> Unit)? = null
    private val meshVerticesBuffer = FloatArray(32 * 32 * 3)
    private val meshWeightsBuffer = FloatArray(32 * 32)
    @Volatile var overlayPanX: Float = 0f
    @Volatile var overlayPanY: Float = 0f
    @Volatile var overlayScale: Float = 1f
    @Volatile var overlayRotationDeg: Float = 0f
    @Volatile var overlayRotationX: Float = 0f
    @Volatile var overlayRotationY: Float = 0f
    @Volatile var currentMetersPerPixel: Float = 0f
    private var markOffsetX: Float = 0f
    private var markOffsetY: Float = 0f
    private val viewMatrixScratch = FloatArray(16)
    private val projMatrixScratch = FloatArray(16)
    private val mappingViewMatrixScratch = FloatArray(16)
    private val backboneScratch = FloatArray(16)
    private val overlayBaseScratch = FloatArray(16)
    private val overlayLocalScratch = FloatArray(16)
    private val overlayComposedScratch = FloatArray(16)
    private val overlayRigidScratch = FloatArray(16)
    private val designFootprintLock = Any()
    private val designRigidModel = FloatArray(16)
    private val designAnchorInvScratch = FloatArray(16)
    private val designMoveDetector = com.hereliesaz.graffitixr.feature.ar.anchor.DesignMoveDetector()
    private var designPlaced = false
    private val contentRotationScratch = FloatArray(16)
    private val contentRotationTemp = FloatArray(16)
    private val contentRotationMul = FloatArray(16)

    private fun buildContentRotation(rx: Float, ry: Float): FloatArray? {
        if (rx == 0f && ry == 0f) return null
        val d = OverlayRenderer.QUAD_HALF_EXTENT * CONTENT_PERSPECTIVE_FACTOR
        android.opengl.Matrix.setIdentityM(contentRotationScratch, 0)
        if (ry != 0f) {
            val rad = Math.toRadians(ry.toDouble())
            contentRotationScratch[0] = kotlin.math.cos(rad).toFloat()
            contentRotationScratch[3] = kotlin.math.sin(rad).toFloat() / d
        }
        if (rx != 0f) {
            val rad = Math.toRadians(rx.toDouble())
            android.opengl.Matrix.setIdentityM(contentRotationTemp, 0)
            contentRotationTemp[5] = kotlin.math.cos(rad).toFloat()
            contentRotationTemp[7] = kotlin.math.sin(rad).toFloat() / d
            android.opengl.Matrix.multiplyMM(
                contentRotationMul, 0, contentRotationScratch, 0, contentRotationTemp, 0
            )
            System.arraycopy(contentRotationMul, 0, contentRotationScratch, 0, 16)
        }
        return contentRotationScratch
    }

    fun attachSession(session: Session?) {
        sessionLock.withLock {
            this.session = session
            if (session != null) {
                frameCount = 0
                resetCameraStreamWatchdog()
                displayRotationHelper.onResume()
                displayRotationHelper.markGeometryDirty()
                backgroundRenderer.invalidateDisplayGeometry()
                if (isSurfaceCreated) session.setCameraTextureName(backgroundRenderer.textureId)
                flashDirty = false
                applyFlashlightStateLocked(session)
                applyFocusModeLocked(session)
                try {
                    val cameraId = session.cameraConfig.cameraId
                    val manager = context.getSystemService(android.content.Context.CAMERA_SERVICE)
                            as android.hardware.camera2.CameraManager
                    sensorOrientation = manager
                        .getCameraCharacteristics(cameraId)
                        .get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION)
                        ?: 90
                } catch (_: Exception) {
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
        overlayRenderer.setBorderExtent(halfW, halfH)
        overlayRenderer.setExtent(OverlayRenderer.QUAD_HALF_EXTENT, OverlayRenderer.QUAD_HALF_EXTENT)
        quadInitialFitApplied = false
    }

    fun updateFlashlight(isOn: Boolean) {
        isFlashlightRequested = isOn
        flashDirty = true
    }

    fun updateAutoFocus(enabled: Boolean) {
        isAutoFocusRequested = enabled
        focusDirty = true
    }

    @Volatile private var isAutoFocusRequested: Boolean = true
    @Volatile private var focusDirty: Boolean = false

    private fun applyFocusModeLocked(activeSession: Session) {
        val wanted = if (isAutoFocusRequested) Config.FocusMode.AUTO else Config.FocusMode.FIXED
        try {
            val config = activeSession.config
            if (config.focusMode != wanted) {
                config.focusMode = wanted
                activeSession.configure(config)
                onDiag("focus: ${if (isAutoFocusRequested) "AUTO" else "FIXED"}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to set focus mode via ARCore Config")
            onDiag("focus: change rejected (${e.javaClass.simpleName})")
        }
    }

    private fun applyFlashlightStateLocked(activeSession: Session) {
        val wanted = if (isFlashlightRequested) Config.FlashMode.TORCH else Config.FlashMode.OFF
        try {
            val config = activeSession.config
            if (config.flashMode != wanted) {
                config.flashMode = wanted
                activeSession.configure(config)
            }
            flashUnsupportedReported = false
        } catch (e: Exception) {
            Timber.e(e, "Failed to set flash mode via ARCore Config")
            if (isFlashlightRequested && !flashUnsupportedReported) {
                flashUnsupportedReported = true
                onDiag("flashlight: not available on this camera config (${e.javaClass.simpleName})")
                onFlashlightUnavailable()
            }
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        onDiag("surface: onSurfaceCreated start")
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        backgroundRenderer.createOnGlThread(context)
        backgroundRenderer.invalidateDisplayGeometry()
        onDiag("surface: bg prog=${backgroundRenderer.isProgramReady} shader=${backgroundRenderer.shaderLog} tex=${backgroundRenderer.textureId}")
        overlayRenderer.createOnGlThread()
        pointCloudRenderer.createOnGlThread(context)
        planeRenderer.createOnGlThread(context)
        arDebugRenderer.createOnGlThread(context)
        try { perceptionFbo.createOnGlThread(context) } catch (t: Throwable) {
            Timber.e(t, "ARDIAG perception FBO init failed — perception will draw un-throttled")
            onDiag("surface: perceptionFbo FAILED ${t.javaClass.simpleName}")
        }
        isSurfaceCreated = true
        onDiag("surface: done")
        Timber.i("ARDIAG onSurfaceCreated: bgTexture=${backgroundRenderer.textureId}")
        sessionLock.withLock { session?.setCameraTextureName(backgroundRenderer.textureId) }
        startStallWatchdog()
    }

    private fun startStallWatchdog() {
        if (watchdog != null) return
        lastTickMs = android.os.SystemClock.elapsedRealtime()
        watchdog = Thread {
            while (!isDestroying) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                val tick = lastTickMs
                if (tick == 0L || isDestroying) continue
                val age = android.os.SystemClock.elapsedRealtime() - tick
                if (age > 2500 && !stallReported) {
                    stallReported = true
                    val whenText = if (camStreamReported) "camera had already been streaming" else "no camera frame ever arrived"
                    onDiag("RENDER STALLED f=$frameCount step=$lastStep for ${age}ms ($whenText)")
                    reportCameraNotFeeding()
                }
            }
        }.apply { isDaemon = true; name = "ArStallWatchdog"; start() }
    }

    private fun reportCameraNotFeeding() {
        if (cameraNotFeedingReported) return
        cameraNotFeedingReported = true
        onCameraNotFeeding?.invoke()
    }

    fun resetCameraStreamWatchdog() {
        camStreamReported = false
        camStallWarned = false
        stallReported = false
        cameraNotFeedingReported = false
        lastTickMs = android.os.SystemClock.elapsedRealtime()
        camWaitStartMs = lastTickMs
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        surfaceWidth = width
        surfaceHeight = height
        displayRotationHelper.onSurfaceChanged(width, height)
        slamManager.setViewportSize(width, height)
        if (perceptionFbo.ready) perceptionFbo.resize(width, height)
    }

    private fun drawPerceptionLayers(
        frame: Frame,
        activeSession: Session,
        camera: com.google.ar.core.Camera,
        viewMatrix: FloatArray,
        projMatrix: FloatArray,
        scanActive: Boolean,
        voxelRevealMaskActive: Boolean,
        isTracking: Boolean
    ) {
        if (scanActive && !hideVisualization && camera.trackingState == TrackingState.TRACKING) {
            planeRenderer.drawPlanes(activeSession, viewMatrix, projMatrix, camera.pose, gridMode = true)
        }
        val anyLayerOn = showFeaturePoints || showPlaneGrids || showPoints
        if (anyLayerOn && isTracking && (!anchorEstablished || isInPlaneRealignment) && !hideVisualization) {
            if (showFeaturePoints) {
                try { frame.acquirePointCloud().use { arDebugRenderer.update(it) } } catch (_: Exception) { }
                arDebugRenderer.draw(viewMatrix, projMatrix)
            }
            if (showPlaneGrids) planeRenderer.drawPlanes(activeSession, viewMatrix, projMatrix, camera.pose, gridMode = true)
            if (showPoints) cloudAnchorModel(activeSession, camera)?.let { pointCloudRenderer.draw(viewMatrix, projMatrix, it) }
        }
    }

    private fun poseToList(pose: com.google.ar.core.Pose?): List<Float> = try {
        if (pose == null) emptyList() else listOf(
            pose.tx(), pose.ty(), pose.tz(), pose.qx(), pose.qy(), pose.qz(), pose.qw()
        )
    } catch (_: Exception) { emptyList() }

    private fun isAutoRotateEnabled(): Boolean = try {
        android.provider.Settings.System.getInt(
            context.contentResolver,
            android.provider.Settings.System.ACCELEROMETER_ROTATION,
        ) == 1
    } catch (_: Exception) { false }

    private fun effectivePerceptionFps(): Int {
        val laggy = lagThrottleEnabled && perceptionRefreshAvgMs > PERCEPTION_LAG_MS
        return if (systemThrottle || laggy) PERCEPTION_FLOOR_FPS else PERCEPTION_FULL_FPS
    }

    private fun perceptionPoseChanged(view: FloatArray): Boolean {
        var maxDelta = 0f
        for (i in 0 until 16) {
            val d = kotlin.math.abs(view[i] - lastPerceptionView[i])
            if (d > maxDelta) maxDelta = d
        }
        return maxDelta > PERCEPTION_POSE_EPSILON
    }

    private fun idlePoseChanged(view: FloatArray): Boolean {
        var maxDelta = 0f
        for (i in 0 until 16) {
            val d = kotlin.math.abs(view[i] - idlePose[i])
            if (d > maxDelta) maxDelta = d
        }
        return maxDelta > IDLE_POSE_EPSILON
    }

    private fun shouldRunHeavyThisFrame(view: FloatArray, tracking: Boolean): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val active = !anchorEstablished || captureRequested || isCapturingTarget ||
            isInPlaneRealignment || scanPhase == ScanPhase.AMBIENT || gestureInProgress || !tracking
        val moved = !haveIdlePose || idlePoseChanged(view)
        if (active || moved) {
            isIdle = false
            resumeHoldUntilMs = now + RESUME_HOLD_MS
            noMotionSinceMs = 0L
        } else if (now >= resumeHoldUntilMs) {
            if (noMotionSinceMs == 0L) noMotionSinceMs = now
            if (now - noMotionSinceMs >= IDLE_ENTER_DEBOUNCE_MS) isIdle = true
        }
        val targetFps = when {
            !adaptiveRateEnabled -> 0
            isIdle -> idleRateCeilingFps.coerceAtLeast(1)
            activeRateCeilingFps > 0 -> activeRateCeilingFps
            else -> 0
        }
        val heavy = targetFps <= 0 || (now - lastHeavyWorkMs) >= (1000L / targetFps)
        if (heavy) {
            lastHeavyWorkMs = now
            System.arraycopy(view, 0, idlePose, 0, 16)
            haveIdlePose = true
        }
        return heavy
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (isDestroying) return
        frameCount++
        val exportThisFrame = exportRequested
        lastTickMs = android.os.SystemClock.elapsedRealtime()
        lastStep = "lock"

        sessionLock.withLock {
            val activeSession = session ?: return
            lastStep = "setTex"
            activeSession.setCameraTextureName(backgroundRenderer.textureId)
            if (flashDirty) { flashDirty = false; applyFlashlightStateLocked(activeSession) }
            if (focusDirty) { focusDirty = false; applyFocusModeLocked(activeSession) }
            displayRotationHelper.updateSessionIfNeeded(activeSession)
            val frame: Frame = try { activeSession.update() } catch (_: SessionPausedException) { return } catch (e: Exception) {
                Timber.e(e, "ARDIAG ARCore session update failed -> camera black"); return
            }
            latestFrame.set(frame)
            latestFrameTimestampNs = frame.timestamp
            drainHitTestQueue(frame)
            if (frame.timestamp > 0L && !camStreamReported) {
                camStreamReported = true
                onDiag("CAMERA STREAMING f=$frameCount ts=${frame.timestamp} track=${frame.camera.trackingState}")
            }
            val scanActive = !anchorEstablished && ambientScanEnabled && scanPhase == ScanPhase.AMBIENT
            val voxelRevealMaskActive = false
            if (scanActive) backgroundRenderer.updateScanMask(visitedSectorsMask)
            backgroundRenderer.draw(frame, scanActive && !voxelRevealMaskActive, grayscale = false)

            if (pendingAnchorEstablishment) {
                pendingAnchorEstablishment = false
                try {
                    val camera = frame.camera
                    val viewMat = FloatArray(16)
                    camera.getViewMatrix(viewMat, 0)
                    val hits = frame.hitTest(0.5f * surfaceWidth, 0.5f * surfaceHeight)
                    var anchorModelMatrix = FloatArray(16)
                    android.opengl.Matrix.setIdentityM(anchorModelMatrix, 0)
                    val fallbackMatrix = FloatArray(16)
                    android.opengl.Matrix.invertM(fallbackMatrix, 0, viewMat, 0)
                    fallbackMatrix[12] += -fallbackMatrix[8] * 2.0f
                    fallbackMatrix[13] += -fallbackMatrix[9] * 2.0f
                    fallbackMatrix[14] += -fallbackMatrix[10] * 2.0f
                    var chosen: com.google.ar.core.HitResult? = null
                    for (h in hits) {
                        val t = h.trackable
                        if (t is com.google.ar.core.Plane && t.isPoseInPolygon(h.hitPose)) { chosen = h; break }
                        if (chosen == null && (t is com.google.ar.core.DepthPoint || t is com.google.ar.core.Point)) chosen = h
                    }
                    if (chosen == null) chosen = hits.firstOrNull()
                    val camPose = camera.pose
                    val camPosX = camPose.tx(); val camPosY = camPose.ty(); val camPosZ = camPose.tz()
                    var anchor: com.google.ar.core.Anchor? = null
                    var nrmX = 0f; var nrmY = 0f; var nrmZ = 0f; var haveNormal = false
                    if (chosen != null) {
                        val pose = chosen.hitPose
                        val dx = pose.tx() - camPosX; val dy = pose.ty() - camPosY; val dz = pose.tz() - camPosZ
                        val dist = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble())
                        if (dist in 0.1..10.0) {
                            pose.toMatrix(anchorModelMatrix, 0)
                            anchor = activeSession.createAnchor(pose)
                            if (chosen.trackable is com.google.ar.core.Plane) {
                                val axis = FloatArray(3); pose.getTransformedAxis(1, 1f, axis, 0)
                                nrmX = axis[0]; nrmY = axis[1]; nrmZ = axis[2]
                            } else { nrmX = -dx; nrmY = -dy; nrmZ = -dz }
                            haveNormal = true
                        }
                    }
                    if (anchor == null) {
                        anchorModelMatrix = fallbackMatrix
                        anchor = activeSession.createAnchor(com.google.ar.core.Pose(
                            floatArrayOf(anchorModelMatrix[12], anchorModelMatrix[13], anchorModelMatrix[14]),
                            floatArrayOf(0f, 0f, 0f, 1f)
                        ))
                        nrmX = camPosX - anchorModelMatrix[12]; nrmY = camPosY - anchorModelMatrix[13]; nrmZ = camPosZ - anchorModelMatrix[14]
                        haveNormal = true
                    }
                    if (haveNormal) {
                        val toCamX = camPosX - anchorModelMatrix[12]; val toCamY = camPosY - anchorModelMatrix[13]; val toCamZ = camPosZ - anchorModelMatrix[14]
                        if (nrmX * toCamX + nrmY * toCamY + nrmZ * toCamZ < 0f) { nrmX = -nrmX; nrmY = -nrmY; nrmZ = -nrmZ }
                        val len = Math.sqrt((nrmX * nrmX + nrmY * nrmY + nrmZ * nrmZ).toDouble()).toFloat()
                        if (len > 1e-4f) {
                            anchorSurfaceNormal[0] = nrmX / len; anchorSurfaceNormal[1] = nrmY / len; anchorSurfaceNormal[2] = nrmZ / len
                        }
                    }
                    overlayRotationCorrectionPending = true
                    overlayRotationCorrectionRetryFrames = 0
                    slamManager.updateAnchorTransform(anchorModelMatrix)
                    setPrimaryAnchor(anchor)
                    poseFusion.reset()
                    anchorEstablished = true
                    slamManager.markAnchorEstablished()
                    hideVisualization = true
                    onAnchorEstablished()
                } catch (e: Exception) { Timber.e(e, "Failed to establish anchor on GL thread") }
            }

            GLES30.glDepthMask(true)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthFunc(GLES30.GL_LEQUAL)
            val camera = frame.camera
            val isDualLensHardware = activeSession.cameraConfig.stereoCameraUsage == com.google.ar.core.CameraConfig.StereoCameraUsage.REQUIRE_AND_USE
            val viewMatrix = viewMatrixScratch
            val projMatrix = projMatrixScratch
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f)
            val mappingViewMatrix = mappingViewMatrixScratch
            camera.pose.inverse().toMatrix(mappingViewMatrix, 0)
            val intrinsics = camera.imageIntrinsics
            val isTracking = camera.trackingState == TrackingState.TRACKING
            if (!isTracking) poseFusion.markRelocalizing()
            val depthSupported = depthApiEnabled && activeSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            val yawRad = kotlin.math.atan2(-viewMatrix[2].toDouble(), -viewMatrix[10].toDouble())
            val yawDeg = Math.toDegrees(yawRad).toFloat()
            slamManager.setArCoreTrackingState(isTracking)
            val backbone = backboneScratch
            if (anchorEstablished) anchorOrchestrator.getConsensusMatrix(backbone)
            else slamManager.getAnchorTransform()?.let { System.arraycopy(it, 0, backbone, 0, 16) }
            val captureAnchorCam = slamManager.captureAnchorCam
            fusionSkipReason = when {
                !fusionEnabled -> com.hereliesaz.graffitixr.common.model.FusionState.DISABLED
                !anchorEstablished -> com.hereliesaz.graffitixr.common.model.FusionState.NO_ANCHOR
                captureAnchorCam == null -> if (slamManager.getWallKeypointCount() <= 0)
                    com.hereliesaz.graffitixr.common.model.FusionState.NO_FINGERPRINT
                else com.hereliesaz.graffitixr.common.model.FusionState.NO_CAPTURE_POSE
                else -> null
            }
            val anchorMatrix: FloatArray = if (fusionEnabled && anchorEstablished && captureAnchorCam != null) {
                poseFusion.currentAnchor(backbone, viewMatrix, slamManager.getRelocResult(), captureAnchorCam,
                    slamManager.getCorroborationConfidence().coerceAtLeast(0f))
            } else backbone

            if (overlayRotationCorrectionPending) {
                val nx = anchorSurfaceNormal[0]; val ny = anchorSurfaceNormal[1]; val nz = anchorSurfaceNormal[2]
                android.opengl.Matrix.setIdentityM(overlayRotationCorrectionCandidate, 0)
                var haveCandidate = false
                if (nx != 0f || ny != 0f || nz != 0f) {
                    var upX: Float; var upY: Float; var upZ: Float
                    if (overlayRotationCorrectionRetryFrames == 0) {
                        upX = 0f; upY = 1f; upZ = 0f
                        if (kotlin.math.abs(ny) > 0.95f) { upX = viewMatrix[1]; upY = viewMatrix[5]; upZ = viewMatrix[9] }
                        var dot = upX * nx + upY * ny + upZ * nz
                        var yX = upX - dot * nx; var yY = upY - dot * ny; var yZ = upZ - dot * nz
                        val yLen = kotlin.math.sqrt(yX * yX + yY * yY + yZ * yZ)
                        if (yLen <= 1e-4f) { upX = viewMatrix[2]; upY = viewMatrix[6]; upZ = viewMatrix[10] }
                        overlayRotationCorrectionUpSnapshot[0] = upX
                        overlayRotationCorrectionUpSnapshot[1] = upY
                        overlayRotationCorrectionUpSnapshot[2] = upZ
                    } else {
                        upX = overlayRotationCorrectionUpSnapshot[0]; upY = overlayRotationCorrectionUpSnapshot[1]; upZ = overlayRotationCorrectionUpSnapshot[2]
                    }
                    val dot = upX * nx + upY * ny + upZ * nz
                    var yX = upX - dot * nx; var yY = upY - dot * ny; var yZ = upZ - dot * nz
                    val yLen = kotlin.math.sqrt(yX * yX + yY * yY + yZ * yZ)
                    if (yLen > 1e-4f) {
                        yX /= yLen; yY /= yLen; yZ /= yLen
                        val xX = yY * nz - yZ * ny; val xY = yZ * nx - yX * nz; val xZ = yX * ny - yY * nx
                        android.opengl.Matrix.setIdentityM(overlayTargetBasisScratch, 0)
                        overlayTargetBasisScratch[0] = xX; overlayTargetBasisScratch[1] = xY; overlayTargetBasisScratch[2] = xZ
                        overlayTargetBasisScratch[4] = yX; overlayTargetBasisScratch[5] = yY; overlayTargetBasisScratch[6] = yZ
                        overlayTargetBasisScratch[8] = nx; overlayTargetBasisScratch[9] = ny; overlayTargetBasisScratch[10] = nz
                        System.arraycopy(anchorMatrix, 0, overlayRotScratch, 0, 16)
                        overlayRotScratch[12] = 0f; overlayRotScratch[13] = 0f; overlayRotScratch[14] = 0f
                        android.opengl.Matrix.transposeM(overlayRotScratch2, 0, overlayRotScratch, 0)
                        android.opengl.Matrix.multiplyMM(overlayRotationCorrectionCandidate, 0, overlayTargetBasisScratch, 0, overlayRotScratch2, 0)
                        haveCandidate = true
                    }
                }
                val normalIsDegenerate = nx == 0f && ny == 0f && nz == 0f
                var reproducesNormal = normalIsDegenerate
                if (!normalIsDegenerate && haveCandidate) {
                    System.arraycopy(anchorMatrix, 0, overlayRotScratch, 0, 16)
                    overlayRotScratch[12] = 0f; overlayRotScratch[13] = 0f; overlayRotScratch[14] = 0f
                    android.opengl.Matrix.multiplyMM(overlayRotScratch2, 0, overlayRotationCorrectionCandidate, 0, overlayRotScratch, 0)
                    val composedDot = overlayRotScratch2[8] * nx + overlayRotScratch2[9] * ny + overlayRotScratch2[10] * nz
                    reproducesNormal = composedDot >= 0.99f
                }
                val anchorTracking = normalIsDegenerate || activeAnchorCount() > 0
                val valid = anchorTracking && reproducesNormal
                when (decideOverlayRotationCorrection(valid, overlayRotationCorrectionRetryFrames, MAX_OVERLAY_CORRECTION_RETRY_FRAMES)) {
                    OverlayRotationCorrectionDecision.APPLY -> {
                        System.arraycopy(overlayRotationCorrectionCandidate, 0, overlayRotationCorrection, 0, 16)
                        overlayRotationCorrectionPending = false
                        overlayRotationCorrectionRetryFrames = 0
                    }
                    OverlayRotationCorrectionDecision.DISCARD -> {
                        Timber.w(
                            "ARDIAG overlayRotationCorrection: discarding invalid capture after " +
                                "$overlayRotationCorrectionRetryFrames retries " +
                                "(anchorTracking=$anchorTracking reproducesNormal=$reproducesNormal); keeping existing/raw anchor orientation"
                        )
                        overlayRotationCorrectionPending = false
                        overlayRotationCorrectionRetryFrames = 0
                    }
                    OverlayRotationCorrectionDecision.RETRY -> overlayRotationCorrectionRetryFrames++
                }
            }

            if (frameCount % 4 == 0) {
                val centerDepth = smoothedCenterDepth
                val viewMatrixSnapshot = viewMatrix.copyOf()
                val anchorMatrixSnapshot = anchorMatrix.copyOf()
                backgroundScope.launch {
                    val count = pointCloudRenderer.accumulatedPointCount
                    var relDir: Triple<Float, Float, Float>? = null
                    val distanceMeters = run {
                        if (!anchorEstablished) return@run -1f
                        val camPose = FloatArray(16)
                        android.opengl.Matrix.invertM(camPose, 0, viewMatrixSnapshot, 0)
                        val dx = anchorMatrixSnapshot[12] - camPose[12]
                        val dy = anchorMatrixSnapshot[13] - camPose[13]
                        val dz = anchorMatrixSnapshot[14] - camPose[14]
                        val len = kotlin.math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
                        if (len > 0.01f) {
                            val localX = dx * viewMatrixSnapshot[0] + dy * viewMatrixSnapshot[4] + dz * viewMatrixSnapshot[8]
                            val localY = dx * viewMatrixSnapshot[1] + dy * viewMatrixSnapshot[5] + dz * viewMatrixSnapshot[9]
                            val localZ = dx * viewMatrixSnapshot[2] + dy * viewMatrixSnapshot[6] + dz * viewMatrixSnapshot[10]
                            relDir = Triple(localX / len, localY / len, localZ / len)
                        }
                        val fwdDot = dx * (-viewMatrixSnapshot[2]) + dy * (-viewMatrixSnapshot[6]) + dz * (-viewMatrixSnapshot[10])
                        if (len > 0.01f && fwdDot > 0f) len else -1f
                    }
                    onTrackingUpdated(isTracking, count, depthSupported, yawDeg, distanceMeters, relDir, isDualLensHardware, centerDepth)
                }
            }

            if (captureRequested) {
                captureRequested = false
                try {
                    frame.acquireCameraImage().use { image ->
                        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                        YuvConverter.yuvToRgbaBitmap(image, bitmap)
                        val displayDegrees = displayRotationHelper.getRotation() * 90
                        val rotationNeeded = (sensorOrientation - displayDegrees + 360) % 360
                        lastCaptureRotationNeededDeg = rotationNeeded
                        val fx = intrinsics.focalLength[0]; val fy = intrinsics.focalLength[1]
                        val cx = intrinsics.principalPoint[0]; val cy = intrinsics.principalPoint[1]
                        val dims = intrinsics.imageDimensions
                        val intrArr = com.hereliesaz.graffitixr.feature.ar.anchor.CaptureRotation.rotateIntrinsics(
                            fx, fy, cx, cy, dims[0].toFloat(), dims[1].toFloat(), rotationNeeded)
                        val captureEnvironment = com.hereliesaz.graffitixr.common.model.CaptureEnvironment(
                            capturedAtEpochMs = System.currentTimeMillis(),
                            elapsedRealtimeNs = android.os.SystemClock.elapsedRealtimeNanos(),
                            frame = com.hereliesaz.graffitixr.common.model.FrameOrientation(
                                displayRotationDeg = displayDegrees,
                                sensorOrientationDeg = sensorOrientation,
                                rotationNeededDeg = rotationNeeded,
                                autoRotateEnabled = isAutoRotateEnabled(),
                            ),
                            attitude = attitudeSampler?.invoke(),
                            arPoses = com.hereliesaz.graffitixr.common.model.ArPoseSnapshot(
                                cameraPose = poseToList(camera.pose),
                                displayOrientedPose = poseToList(camera.displayOrientedPose),
                                androidSensorPose = poseToList(frame.androidSensorPose),
                            ),
                            location = locationSampler?.invoke(),
                        )
                        onTargetCaptured(bitmap, image.width, image.height, null, 0, 0, 0,
                            intrArr, mappingViewMatrix.copyOf(), rotationNeeded, -1f, null, captureEnvironment)
                    }
                } catch (e: Exception) { Timber.e(e, "Failed to capture target frame") }
            }

            if (isTracking && frameCount % (if (anchorEstablished) 10 else 3) == 0) {
                checkCloudAnchorLiveness()
                if (!anchorEstablished) {
                    try {
                        cloudAnchorModel(activeSession, camera)
                        val anchorPose = cloudAnchor?.takeIf { it.trackingState == TrackingState.TRACKING }?.pose
                        if (anchorPose != null) {
                            anchorPose.inverse().toMatrix(worldToCloudAnchorScratch, 0)
                            frame.acquirePointCloud().use { pointCloudRenderer.update(it, worldToCloudAnchorScratch) }
                        }
                    } catch (_: Exception) { }
                }
            }

            if ((!anchorEstablished || isInPlaneRealignment) && frameCount % 30 == 0) {
                try { refineAnchorFromBestPlane(activeSession, viewMatrix) } catch (_: Exception) { }
            }

            if (overlayBitmapDirty) {
                overlayBitmapDirty = false
                val bmp = pendingOverlayBitmap
                if (bmp != null) overlayRenderer.updateTexture(bmp) else overlayRenderer.clearTexture()
            }

            if (!quadInitialFitApplied && anchorEstablished && overlayRenderer.hasTexture && lastBitmapW > 0 && lastBitmapH > 0) {
                val camWorld = FloatArray(16)
                android.opengl.Matrix.invertM(camWorld, 0, viewMatrix, 0)
                val ddx = anchorMatrix[12] - camWorld[12]; val ddy = anchorMatrix[13] - camWorld[13]; val ddz = anchorMatrix[14] - camWorld[14]
                val dist = kotlin.math.sqrt((ddx * ddx + ddy * ddy + ddz * ddz).toDouble()).toFloat().coerceAtLeast(0.5f)
                val halfScreenW = if (projMatrix[0] != 0f) dist / projMatrix[0] * 0.9f else dist * 0.9f
                val halfScreenH = if (projMatrix[5] != 0f) dist / projMatrix[5] * 0.9f else dist * 0.9f
                val bmpAspect = lastBitmapW.toFloat() / lastBitmapH.toFloat()
                val halfWFromH = halfScreenH * bmpAspect
                val halfW: Float; val halfH: Float
                if (halfWFromH <= halfScreenW) { halfW = halfWFromH; halfH = halfScreenH }
                else { halfW = halfScreenW; halfH = halfScreenW / bmpAspect }
                overlayRenderer.setExtent(halfW, halfH)
                quadInitialFitApplied = true
            }

            val hasMeshData = false
            System.arraycopy(anchorMatrix, 0, overlayBaseScratch, 0, 16)
            if (!anchorEstablished) {
                anchorSurfaceNormal[0] = 0f; anchorSurfaceNormal[1] = 0f; anchorSurfaceNormal[2] = 0f
            }
            if (anchorSurfaceNormal[0] != 0f || anchorSurfaceNormal[1] != 0f || anchorSurfaceNormal[2] != 0f) {
                System.arraycopy(anchorMatrix, 0, overlayRotScratch, 0, 16)
                overlayRotScratch[12] = 0f; overlayRotScratch[13] = 0f; overlayRotScratch[14] = 0f
                android.opengl.Matrix.multiplyMM(overlayRotScratch2, 0, overlayRotationCorrection, 0, overlayRotScratch, 0)
                overlayBaseScratch[0] = overlayRotScratch2[0]; overlayBaseScratch[1] = overlayRotScratch2[1]; overlayBaseScratch[2] = overlayRotScratch2[2]
                overlayBaseScratch[4] = overlayRotScratch2[4]; overlayBaseScratch[5] = overlayRotScratch2[5]; overlayBaseScratch[6] = overlayRotScratch2[6]
                overlayBaseScratch[8] = overlayRotScratch2[8]; overlayBaseScratch[9] = overlayRotScratch2[9]; overlayBaseScratch[10] = overlayRotScratch2[10]
            }
            android.opengl.Matrix.setIdentityM(overlayLocalScratch, 0)
            android.opengl.Matrix.translateM(overlayLocalScratch, 0, markOffsetX + overlayPanX, markOffsetY + overlayPanY, 0f)
            android.opengl.Matrix.rotateM(overlayLocalScratch, 0, overlayRotationDeg, 0f, 0f, 1f)
            android.opengl.Matrix.multiplyMM(overlayRigidScratch, 0, overlayBaseScratch, 0, overlayLocalScratch, 0)
            android.opengl.Matrix.scaleM(overlayLocalScratch, 0, overlayScale, overlayScale, 1f)
            android.opengl.Matrix.multiplyMM(overlayComposedScratch, 0, overlayBaseScratch, 0, overlayLocalScratch, 0)
            overlayRenderer.draw(viewMatrix, projMatrix, overlayComposedScratch,
                if (hasMeshData) meshVerticesBuffer else null,
                if (hasMeshData) meshWeightsBuffer else null,
                buildContentRotation(overlayRotationX, overlayRotationY))

            if (perceptionFbo.ready && perceptionFbo.isSized()) {
                val nowMs = android.os.SystemClock.elapsedRealtime()
                val due = nowMs - lastPerceptionRefreshMs >= 1000f / effectivePerceptionFps()
                val moved = perceptionPoseChanged(viewMatrix)
                val mappedPoints = pointCloudRenderer.accumulatedPointCount
                val mapGrew = mappedPoints != lastPerceptionPointCount
                val dissolving = nowMs < planeRenderer.dissolveCompletesAtMs
                if (!havePerceptionCache || moved || (due && (mapGrew || dissolving))) {
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    perceptionFbo.bindForRender()
                    drawPerceptionLayers(frame, activeSession, camera, viewMatrix, projMatrix, scanActive, voxelRevealMaskActive, isTracking)
                    perceptionFbo.unbind(surfaceWidth, surfaceHeight)
                    System.arraycopy(viewMatrix, 0, lastPerceptionView, 0, 16)
                    lastPerceptionRefreshMs = nowMs
                    lastPerceptionPointCount = mappedPoints
                    havePerceptionCache = true
                    val dt = (android.os.SystemClock.elapsedRealtime() - t0).toFloat()
                    perceptionRefreshAvgMs = perceptionRefreshAvgMs * 0.9f + dt * 0.1f
                }
                perceptionFbo.composite(reveal = voxelRevealMaskActive, dim = 0.85f)
            } else {
                drawPerceptionLayers(frame, activeSession, camera, viewMatrix, projMatrix, scanActive, voxelRevealMaskActive, isTracking)
            }

            if (exportThisFrame) {
                exportRequested = false
                try {
                    val callback = onExportCaptured
                    val w = surfaceWidth; val h = surfaceHeight
                    if (callback != null && w > 0 && h > 0) {
                        val buf = ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder())
                        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
                        buf.rewind()
                        val flipped = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        val source = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        source.copyPixelsFromBuffer(buf)
                        val canvas = android.graphics.Canvas(flipped)
                        val matrix = android.graphics.Matrix().apply { postScale(1f, -1f, w / 2f, h / 2f) }
                        canvas.drawBitmap(source, matrix, null)
                        source.recycle()
                        callback(flipped)
                        onExportCaptured = null
                    }
                } catch (e: Exception) { Timber.e(e, "Failed to capture export frame") }
            }
            lastStep = "frameDone"
        }
    }

    fun setPrimaryAnchor(anchor: com.google.ar.core.Anchor) {
        anchorOrchestrator.setInitialAnchor(anchor)
    }

    private fun refineAnchorFromBestPlane(session: Session, viewMatrix: FloatArray) {
        val cameraMat = FloatArray(16)
        android.opengl.Matrix.invertM(cameraMat, 0, viewMatrix, 0)
        val camX = cameraMat[12]; val camY = cameraMat[13]; val camZ = cameraMat[14]
        val fwdX = -cameraMat[8]; val fwdY = -cameraMat[9]; val fwdZ = -cameraMat[10]
        val planes = session.getAllTrackables(com.google.ar.core.Plane::class.java)
        var bestPlane: com.google.ar.core.Plane? = null
        var bestDot = 0f; var bestArea = 0f
        for (plane in planes) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) continue
            val pose = plane.centerPose
            val dx = pose.tx() - camX; val dy = pose.ty() - camY; val dz = pose.tz() - camZ
            val len = kotlin.math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
            if (len < 0.3f || len > 15f) continue
            val dot = (dx * fwdX + dy * fwdY + dz * fwdZ) / len
            if (dot < PLANE_PICK_MIN_DOT) continue
            val area = plane.extentX * plane.extentZ
            val better = when {
                bestPlane == null -> true
                dot > bestDot + PLANE_PICK_DOT_TIE -> true
                dot < bestDot - PLANE_PICK_DOT_TIE -> false
                else -> area > bestArea
            }
            if (better) { bestDot = kotlin.math.max(bestDot, dot); bestArea = area; bestPlane = plane }
        }
        val plane = bestPlane ?: return
        val planeMatrix = FloatArray(16); plane.centerPose.toMatrix(planeMatrix, 0)
        val nx = planeMatrix[4]; val ny = planeMatrix[5]; val nz = planeMatrix[6]
        val nDotD = nx * fwdX + ny * fwdY + nz * fwdZ
        if (kotlin.math.abs(nDotD) < 0.0001f) return
        val t = ((planeMatrix[12] - camX) * nx + (planeMatrix[13] - camY) * ny + (planeMatrix[14] - camZ) * nz) / nDotD
        if (t < 0.1f) return
        val hitX = camX + fwdX * t; val hitY = camY + fwdY * t; val hitZ = camZ + fwdZ * t
        val zx = nx; val zy = ny; val zz = nz
        var refX = 0f; var refY = 1f; var refZ = 0f
        if (kotlin.math.abs(zy) > 0.9f) { refX = 1f; refY = 0f; refZ = 0f }
        var xx = refY * zz - refZ * zy; var xy = refZ * zx - refX * zz; var xz = refX * zy - refY * zx
        val xLen = kotlin.math.sqrt((xx * xx + xy * xy + xz * xz).toDouble()).toFloat()
        if (xLen < 0.0001f) return
        xx /= xLen; xy /= xLen; xz /= xLen
        val yx = zy * xz - zz * xy; val yy = zz * xx - zx * xz; val yz = zx * xy - zy * xx
        val anchorMat = FloatArray(16)
        android.opengl.Matrix.setIdentityM(anchorMat, 0)
        anchorMat[0] = xx; anchorMat[1] = xy; anchorMat[2] = xz
        anchorMat[4] = yx; anchorMat[5] = yy; anchorMat[6] = yz
        anchorMat[8] = zx; anchorMat[9] = zy; anchorMat[10] = zz
        anchorMat[12] = hitX; anchorMat[13] = hitY; anchorMat[14] = hitZ
        slamManager.updateAnchorTransform(anchorMat)
    }

    fun releaseGlResources() {
        backgroundRenderer.release()
        overlayRenderer.release()
        try { cloudAnchor?.detach() } catch (_: Exception) { }
        cloudAnchor = null
        pointCloudRenderer.release()
        planeRenderer.release()
        arDebugRenderer.release()
        perceptionFbo.release()
    }

    fun detachSessionBounded(timeoutMs: Long): Boolean {
        val locked = try { sessionLock.tryLock(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
        try { session = null } finally { if (locked) sessionLock.unlock() }
        return locked
    }

    fun <T> withLockedSession(timeoutMs: Long = 1500L, block: (Session) -> T): T? {
        val locked = try { sessionLock.tryLock(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
        if (!locked) return null
        try { val s = session ?: return null; return block(s) } finally { sessionLock.unlock() }
    }

    fun requestResume(timeoutMs: Long = 1500L): SessionLifecycleOutcome {
        val locked = try { sessionLock.tryLock(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
        if (!locked) return SessionLifecycleOutcome.LockTimeout
        try {
            val s = session ?: return SessionLifecycleOutcome.NoSession
            s.resume(); return SessionLifecycleOutcome.Applied
        } catch (e: Exception) { return SessionLifecycleOutcome.Failed(e) } finally { sessionLock.unlock() }
    }

    fun requestPause(timeoutMs: Long = 1500L): SessionLifecycleOutcome {
        val locked = try { sessionLock.tryLock(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
        if (!locked) return SessionLifecycleOutcome.LockTimeout
        try {
            val s = session ?: return SessionLifecycleOutcome.NoSession
            s.pause(); return SessionLifecycleOutcome.Applied
        } catch (e: Exception) { return SessionLifecycleOutcome.Failed(e) } finally { sessionLock.unlock() }
    }

    fun destroy() {
        isDestroying = true
        watchdog?.interrupt()
        watchdog = null
        backgroundScope.cancel("Renderer detached and destroyed.")
        val locked = try { sessionLock.tryLock(500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
        try {
            session = null
            // clear() is Kotlin-state-only and deliberately never calls Anchor.detach(). Native anchor
            // detaches happen only during serialized replacement in setInitialAnchor(); final native
            // lifetime is owned by Session.close(). This stays safe even when tryLock timed out.
            anchorOrchestrator.clear()
        } finally { if (locked) sessionLock.unlock() }
        failPendingHitTests()
        pendingOverlayBitmap = null
        latestFrame.set(null)
        latestFrameTimestampNs = 0L
    }

    private companion object {
        const val DOODLE_MIN_RELOC_INLIERS = 20
        const val CONTENT_PERSPECTIVE_FACTOR = 3.0f
        const val PLANE_PICK_MIN_DOT = 0.4f
        const val PLANE_PICK_DOT_TIE = 0.05f
        // Invalid candidates are retried for a bounded interval, then DISCARDED. Time passing is not
        // evidence that an unvalidated orientation is safe to make authoritative.
        const val MAX_OVERLAY_CORRECTION_RETRY_FRAMES = 15
        const val PERCEPTION_FULL_FPS = 60
        const val PERCEPTION_FLOOR_FPS = 30
        const val PERCEPTION_LAG_MS = 16f
        const val PERCEPTION_POSE_EPSILON = 1.5e-3f
        const val IDLE_POSE_EPSILON = 1.5e-3f
        const val IDLE_ENTER_DEBOUNCE_MS = 700L
        const val RESUME_HOLD_MS = 500L
    }
}

private fun angularVelocity(prev: FloatArray, cur: FloatArray, dt: Float): FloatArray {
    if (dt <= 0f) return floatArrayOf(0f, 0f, 0f)
    val px = -prev[0]; val py = -prev[1]; val pz = -prev[2]; val pw = prev[3]
    val cx = cur[0]; val cy = cur[1]; val cz = cur[2]; val cw = cur[3]
    var rw = cw * pw - cx * px - cy * py - cz * pz
    var rx = cw * px + cx * pw + cy * pz - cz * py
    var ry = cw * py - cx * pz + cy * pw + cz * px
    var rz = cw * pz + cx * py - cy * px + cz * pw
    val n = kotlin.math.sqrt(rw * rw + rx * rx + ry * ry + rz * rz)
    if (n <= 1e-6f) return floatArrayOf(0f, 0f, 0f)
    rw /= n; rx /= n; ry /= n; rz /= n
    if (rw < 0f) { rw = -rw; rx = -rx; ry = -ry; rz = -rz }
    val angle = 2f * kotlin.math.acos(rw.coerceIn(-1f, 1f))
    val s = kotlin.math.sqrt(1f - rw * rw)
    if (s <= 1e-6f) return floatArrayOf(0f, 0f, 0f)
    val k = angle / (s * dt)
    return floatArrayOf(rx * k, ry * k, rz * k)
}
