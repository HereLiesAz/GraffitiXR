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

class ArRenderer(
    private val context: Context,
    private val slamManager: SlamManager,
    // Last arg is the camera→point distance (meters) at the tapped pixel, or -1f when unavailable.
    private val onTargetCaptured: (Bitmap, Int, Int, ByteBuffer?, Int, Int, Int, FloatArray?, FloatArray, Int, Float, FloatArray?, com.hereliesaz.graffitixr.common.model.CaptureEnvironment) -> Unit,
    private val onTrackingUpdated: (Boolean, Int, Int, Boolean, Float, Float, Triple<Float, Float, Float>?, Boolean, Boolean, Float, Float, Float) -> Unit,
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
    // Fired from the GL thread when ARCore has been stuck not-tracking for a
    // sustained run of frames (true) or recovers (false). MainScreen uses this
    // to drop the GLSurfaceView render mode to WHEN_DIRTY so the saturated
    // main thread can serve input again.
    private val onTrackingLoopStuck: (Boolean) -> Unit = {},
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

    // Consecutive frames the ARCore camera has reported a non-TRACKING state.
    // When this crosses STUCK_THRESHOLD we fire onTrackingLoopStuck(true) so
    // the host can downgrade GL render mode. We fire (false) on recovery.
    private var consecutiveNotTrackingFrames = 0
    private var trackingLoopStuckReported = false
    private val stuckThresholdFrames = 30

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
     * [sessionLock].
     */
    val latestFrame: AtomicReference<Frame?> = AtomicReference(null)

    private class PendingHitTest(
        val x: Float,
        val y: Float,
        val result: kotlinx.coroutines.CompletableDeferred<FloatArray?>
    )
    private val hitTestQueue = java.util.concurrent.ConcurrentLinkedQueue<PendingHitTest>()

    /**
     * Thread-safe ARCore hit test: the request is queued and executed inside the next
     * [onDrawFrame] under [sessionLock], serialized with update()/configure() like every other
     * Session access. Completes with the hit pose translation (x, y, z) or null (no hit, no
     * session, or renderer destroyed). Callers should await with a timeout — if the render loop
     * is paused, no frame will arrive to serve the request.
     */
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
                frame.hitTest(req.x, req.y).firstOrNull()?.hitPose?.translation
            } catch (e: Exception) {
                Timber.w(e, "queued hitTest failed")
                null
            }
            req.result.complete(translation)
        }
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
    // Diagnostic "what is the AR seeing" view: current-frame ARCore feature points (yellow) +
    // tracked planes, drawn over the camera whenever showArDebugView is set. Tied by MainScreen
    // to the Diagnostic Overlay setting. Separate from pointCloudRenderer, which accumulates and
    // persists with the project.
    private val arDebugRenderer = ArDebugRenderer()
    // Independent perception-layer toggles (Settings; default on). Drawn while in AR and tracking,
    // suppressed during target capture. Each governs one layer of "what the AR is seeing".
    @Volatile var showFeaturePoints: Boolean = true  // ARCore tracker landmarks (yellow dots)
    @Volatile var showPlaneGrids: Boolean = true      // detected planes as metric grids
    @Volatile var showPoints: Boolean = true          // accumulated sparse point cloud

    // --- Throttled perception (FBO-cached) -----------------------------------------------------
    // World-locked perception is redrawn into an offscreen buffer only when the pose moves or the
    // map grows, capped at a selectable rate, and composited every frame. Camera + overlay +
    // gestures keep full display rate; the expensive voxel/coverage passes run at the cap. See
    // PerceptionFbo. If the FBO is unavailable, perception falls back to drawing every frame.
    private val perceptionFbo = PerceptionFbo()
    // Perception redraws at PERCEPTION_FULL_FPS normally and drops to PERCEPTION_FLOOR_FPS while a
    // throttle trigger is active. systemThrottle is OR-ed from the *enabled* thermal / power-save /
    // low-battery triggers by ArViewModel; lagThrottleEnabled gates the renderer's own measured-lag
    // trigger. All four triggers are user-toggleable in Settings.
    @Volatile var systemThrottle: Boolean = false
    @Volatile var lagThrottleEnabled: Boolean = true
    private var lastPerceptionRefreshMs = 0L
    private val lastPerceptionView = FloatArray(16)
    private var havePerceptionCache = false
    private var lastPerceptionSplatCount = -1
    private var perceptionRefreshAvgMs = 0f

    // --- Adaptive idle rate (pushed from ArViewModel via MainScreen) -----------------------------
    // When the projection is locked and the phone is held still, the heavy native SLAM/VIO map
    // integration is gated to idleRateCeilingFps; it snaps back to full rate instantly on motion or
    // interaction. session.update() and the cheap camera+overlay draw keep running every vsync, so
    // tracking stays healthy and a static scene looks identical — the saving is the gated SLAM work.
    @Volatile var adaptiveRateEnabled: Boolean = true
    @Volatile var idleRateCeilingFps: Int = 12
    @Volatile var activeRateCeilingFps: Int = 0   // 0 = uncapped
    @Volatile var gestureInProgress: Boolean = false
    /** True while the pipeline is in the gated idle state. Read-only for callers (e.g. DualAnalyzer). */
    @Volatile var isIdle: Boolean = false
        private set
    // GL-thread-only idle bookkeeping.
    private val idlePose = FloatArray(16)
    private var haveIdlePose = false
    private var noMotionSinceMs = 0L
    private var resumeHoldUntilMs = 0L
    private var lastHeavyWorkMs = 0L

    private val anchorOrchestrator = AnchorOrchestrator()
    private val poseFusion = com.hereliesaz.graffitixr.feature.ar.anchor.PoseFusion()
    // A/B switch for the Sub-project A harness: when false, the overlay is driven purely by the
    // world-locked ARCore anchor (the stable pre/post-anchor behavior). When true, the mark-PnP
    // relocalization correction is fused on top — which, for a richly-detailed fingerprint (e.g. a
    // painting) that relocalizes nearly every frame, can yank the overlay to a camera-relative pose
    // and make it ride the screen. Default OFF so the artwork stays glued to the real-world anchor;
    // re-enable once the reloc pose convention is verified world-consistent for image fingerprints.
    @Volatile var fusionEnabled: Boolean = false
    // Whether the ARCore Depth API is actually enabled this session. Off by default — it starves VIO
    // on this hardware; metric depth comes from triangulation/stereo instead. Set from ArViewModel.
    @Volatile var depthApiEnabled: Boolean = false

    /** Whether the 360-degree ambient sweep is required. See ArUiState.ambientScanEnabled. */
    @Volatile var ambientScanEnabled: Boolean = true

    // Eval (Sub-project A): null unless dev/eval mode is on. Set from ArViewModel.
    @Volatile var driftCostProbe: com.hereliesaz.graffitixr.feature.ar.eval.DriftCostProbe? = null

    /**
     * Latest physical device attitude, or null when unavailable. Supplied by the ViewModel, which
     * owns the sensor lifecycle — the renderer only reads it, and only at capture.
     */
    @Volatile var attitudeSampler: (() -> com.hereliesaz.graffitixr.common.model.DeviceAttitude?)? = null

    /** Latest location fix, or null. Same ownership split as [attitudeSampler]. */
    @Volatile var locationSampler: (() -> com.hereliesaz.graffitixr.common.model.LocationFix?)? = null
    // Scratch holder for the mark-PnP "truth" pose read from the native anchor transform.
    private val truthPoseScratch = FloatArray(16)
    // Visible-confidence threshold above which mark-PnP is treated as truth for eval.
    /**
     * Inliers required before a relocalization is trusted as eval ground truth.
     *
     * Matches PoseFusion's own bar for a usable fix rather than inventing a second one: a pose good
     * enough to move the overlay is good enough to measure against, and two different thresholds
     * would make the CSV disagree with what the app actually did.
     */
    private val MIN_TRUTH_INLIERS = 6

    @Volatile var showAnchorBoundary: Boolean = false
    @Volatile var anchorEstablished: Boolean = false
        set(value) {
            field = value
            if (!value) {
                anchorOrchestrator.clear()
                quadInitialFitApplied = false
                // Anchor cleared → the artist is back to scanning, so the voxel/perception map
                // must become visible again. (It was hidden when the anchor was established.)
                hideVisualization = false
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
    // Set by updateFlashlight (any thread); consumed at the top of onDrawFrame so the ARCore
    // configure() runs on the GL thread under sessionLock, never concurrently with update().
    @Volatile private var flashDirty: Boolean = false
    // Guards onFlashlightUnavailable so a rejected torch reports once per request, not once a frame.
    private var flashUnsupportedReported: Boolean = false

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
    // Camera-streaming watchdog: report the first frame ARCore actually delivers (ts>0) and warn once
    // if no camera frame has arrived a few seconds after the session started driving.
    private var camStreamReported = false
    private var camStallWarned = false
    // One-shot: true once the first tracking plane has been reported via onPlaneDetected.
    private var planeDetectedReported = false

    // First-run doodle demo. doodleLockActive is set by the host while the onboarding overlay is up;
    // when active the renderer auto-establishes a wall anchor, and (once the host has built a
    // fingerprint from the user's drawing) tracks the fused mark-PnP pose, firing onDoodleLocked when
    // it holds steady AND relocalization is confident (DOODLE_MIN_RELOC_INLIERS).
    private val anchorLockTracker = AnchorLockTracker()
    private var doodleLockReported = false
    private var doodleAutoAnchorRequested = false
    // Wall plane the doodle anchor landed on: [px,py,pz, nx,ny,nz] in world space. Written on the GL
    // thread at anchor establishment, read off-thread by ArViewModel to build the fingerprint from the
    // user's drawing (no tap). Volatile for safe publication across threads.
    @Volatile
    var doodleWallPlane: FloatArray? = null
        private set

    // @Volatile: set on the UI thread (MainScreen update), read on the GL thread. Setting it true
    // after it was false (a fresh onboarding on this renderer instance) clears the one-shot latches so
    // the auto-anchor + lock can run again — otherwise a retried phase would be permanently disabled.
    @Volatile
    var doodleLockActive: Boolean = false
        set(value) {
            if (value && !field) {
                doodleLockReported = false
                doodleAutoAnchorRequested = false
                doodleWallPlane = null
                anchorLockTracker.reset()
            }
            field = value
        }
    // Render-thread stall watchdog. The GL thread can block inside session.update() forever when the
    // camera never feeds ARCore; a side thread watches these markers and reports the stuck step on
    // screen (the blocked GL thread can't report for itself).
    @Volatile private var lastTickMs = 0L
    @Volatile private var lastStep = "init"
    @Volatile private var stallReported = false
    // When the current camera config started being driven (attach/reconfigure). The no-first-frame
    // verdict is measured against this in wall time, not frames: this device's first camera frame
    // arrives ~3s after resume, which at 30fps blew past the old 90-frame check and produced a
    // false "camera not streaming" alarm every session.
    @Volatile private var camWaitStartMs = 0L
    // Self-heal hook. Fired at most once (off the GL thread) the moment ARCore stops receiving camera
    // frames — i.e. the live session's camera config isn't streaming. Lets the ViewModel reconfigure to
    // a mono config even while the GL thread is blocked in update() and no tracking callback can fire.
    @Volatile private var cameraNotFeedingReported = false
    var onCameraNotFeeding: (() -> Unit)? = null
    private var watchdog: Thread? = null
    // Written under `sessionLock` off the GL thread (attachSession, and its catch fallback) and
    // read on the GL thread without it, so it needs the visibility guarantee. It was a plain var:
    // no happens-before, so the GL thread could keep observing the initializer on a device where
    // the real value is 270. That now decides an eval column's value, not just a rotation.
    //
    // The initializer is also the commonest real value AND the exception fallback, so a
    // getCameraCharacteristics failure yields a plausible, indistinguishable reading. Not changed
    // here — every rotation consumer would need a "sensor orientation unknown" path — but recorded
    // because `captureRotationNeededDeg` inherits it, and E0b's attributability rests on it.
    @Volatile private var sensorOrientation = 90

    /**
     * `rotationNeeded` at the moment the active target was captured, or [NOT_SAMPLED] if this
     * renderer has not performed a capture. **E0b's independent variable**; see
     * `EvalSample.captureRotationNeededDeg`. Set where the capture's geometry is fixed, not where
     * the eval tick happens to run.
     *
     * Stays [NOT_SAMPLED] for a target restored from a saved project — the renderer never saw that
     * capture. That is the honest reading; a 0 would silently file it as E0b's control condition.
     */
    @Volatile
    private var lastCaptureRotationNeededDeg =
        com.hereliesaz.graffitixr.feature.ar.eval.EvalSampleLog.NOT_SAMPLED
    private var isSurfaceCreated = false

    private var lastPoseX = 0f
    private var lastPoseY = 0f
    private var lastPoseZ = 0f
    // ARCore frame timestamp (ns) of the last motion sample, so velocity uses the real interval
    // rather than a hardcoded rate that only matched one of the two feed throttles.
    private var lastMotionSampleNs = 0L
    // Last camera rotation quaternion [x,y,z,w], for delta-based angular velocity.
    private var lastQuat: FloatArray? = null

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
    // World-space surface normal captured at anchor establishment, always oriented to point toward the
    // camera/user. The artwork is ALWAYS laid flat against the selected surface and facing the user by
    // rebuilding the overlay base frame each draw so its local +Z = this normal (see overlayDraw),
    // regardless of whether the anchor landed on a real plane, a depth/feature point, or the fallback.
    // (x,y,z); zero-length until an anchor is established, in which case the raw anchor frame is used.
    private val anchorSurfaceNormal = FloatArray(3)

    @Volatile var exportRequested: Boolean = false
    var onExportCaptured: ((Bitmap) -> Unit)? = null

    // Pre-allocated buffers for Surface Mesh updates (32x32 grid)
    private val meshVerticesBuffer = FloatArray(32 * 32 * 3)
    private val meshWeightsBuffer = FloatArray(32 * 32)

    // --- Whole-design AR overlay transform (the "Layer" item in AR) ---
    // In-plane translation (meters along the overlay's local X/Y), uniform scale, and rotation about
    // the wall normal, applied to the artwork overlay ON TOP of the tracked anchor. Driven by the
    // persisted per-mode adjustment (modeAdjustments[AR]); pushed from MainScreen.
    @Volatile var overlayPanX: Float = 0f
    @Volatile var overlayPanY: Float = 0f
    @Volatile var overlayScale: Float = 1f
    // Rotation of the whole design about the overlay's normal (Z-axis, degrees): spins the
    // artwork in the surface plane. Driven by the persisted per-mode adjustment.
    @Volatile var overlayRotationDeg: Float = 0f
    // Content-level perspective rotation (degrees) matching Compose's graphicsLayer rotationX/Y.
    // Applied as a 2D perspective transform on the texture content — the GL quad stays flat on the
    // wall while the artwork appears tilted, consistent with Overlay/Mockup/Trace modes.
    @Volatile var overlayRotationX: Float = 0f
    @Volatile var overlayRotationY: Float = 0f
    // Meters-per-pixel at the overlay's depth this frame, so the UI can convert a screen-pixel drag
    // into an in-plane translation in meters. 0 until the overlay has been positioned.
    @Volatile var currentMetersPerPixel: Float = 0f
    // In-plane offset (overlay-local meters) that shifts the overlay center from the tracked anchor
    // onto the matched-marks centroid. Recomputed each frame from SlamManager.overlayMarkCenterLocal
    // (the marks centroid in the fingerprint anchor's frame) against the live anchor, so it tracks
    // drift and survives anchor re-establishment and project reload.
    private var markOffsetX: Float = 0f
    private var markOffsetY: Float = 0f
    // Per-frame pose scratch. These were allocated fresh inside onDrawFrame, so at 60 fps they made
    // five 16-float arrays a frame purely to be filled and dropped — allocation churn on the GL
    // thread, which is the one thread whose pauses the artist sees as the overlay stuttering
    // against the wall. Owned by onDrawFrame; not safe to read from another thread.
    private val viewMatrixScratch = FloatArray(16)
    private val projMatrixScratch = FloatArray(16)
    private val mappingViewMatrixScratch = FloatArray(16)
    private val mappingProjMatrixScratch = FloatArray(16)
    private val backboneScratch = FloatArray(16)
    // Scratch for composing the overlay matrix (anchor frame * in-plane transform).
    private val overlayBaseScratch = FloatArray(16)
    private val overlayLocalScratch = FloatArray(16)
    private val overlayComposedScratch = FloatArray(16)
    private val overlayRigidScratch = FloatArray(16)

    // Where the artwork sits (IMPLEMENTATION.md 2.3/2.4), assembled here and handed out through
    // onDesignFootprintChanged.
    //
    // The lock is now GL-thread-only on both sides: the capture-path reader it originally guarded
    // went with `designFootprint()`. It is kept because the four values are only meaningful
    // together, so a future reader from another thread inherits the guarantee rather than having to
    // rediscover the need for it; uncontended, it costs nothing.

    // Guards the design pose while it is composed and copied out. GL-thread-only on both sides now
    // that the capture-path reader is gone, so it states an invariant more than it contends: the
    // pose and the extents handed to onDesignFootprintChanged must come from the same frame.
    private val designFootprintLock = Any()
    private val designRigidModel = FloatArray(16)
    private val designAnchorInvScratch = FloatArray(16)

    // Extracted so the two rules it has to obey — compare against the last PUBLISH not the last
    // frame, and take only drift-immune inputs — are pinned by tests instead of comments. Each was
    // violated once here, and neither was reachable from a test while it lived inside onDrawFrame.
    private val designMoveDetector =
        com.hereliesaz.graffitixr.feature.ar.anchor.DesignMoveDetector()

    private var designPlaced = false

    // Scratch for the 2D perspective content rotation matrix (X/Y axes).
    private val contentRotationScratch = FloatArray(16)
    private val contentRotationTemp = FloatArray(16)
    private val contentRotationMul = FloatArray(16)

    /**
     * Builds a 4×4 column-major matrix that applies 2D perspective rotation for the given X/Y
     * angles (degrees), matching Compose's `graphicsLayer { rotationX; rotationY }`.
     * The matrix warps vertex positions in the overlay's local XY plane (Z stays 0, W encodes
     * perspective) so the shader can divide by W and feed standard W=1 vertices to the MVP pipeline.
     * Returns null when both angles are zero (identity — the shader uses its own identity fallback).
     */
    private fun buildContentRotation(rx: Float, ry: Float): FloatArray? {
        if (rx == 0f && ry == 0f) return null
        // Camera distance relative to the overlay's half-extent. Higher = subtler perspective.
        val d = OverlayRenderer.QUAD_HALF_EXTENT * CONTENT_PERSPECTIVE_FACTOR

        // Build Y perspective matrix: foreshortens left/right edges.
        // Column-major: [cosY,0,0,sinY/d, 0,1,0,0, 0,0,1,0, 0,0,0,1]
        android.opengl.Matrix.setIdentityM(contentRotationScratch, 0)
        if (ry != 0f) {
            val rad = Math.toRadians(ry.toDouble())
            contentRotationScratch[0] = kotlin.math.cos(rad).toFloat()
            contentRotationScratch[3] = kotlin.math.sin(rad).toFloat() / d
        }

        if (rx != 0f) {
            val rad = Math.toRadians(rx.toDouble())
            // Build X perspective matrix: foreshortens top/bottom edges.
            // Column-major: [1,0,0,0, 0,cosX,0,sinX/d, 0,0,1,0, 0,0,0,1]
            android.opengl.Matrix.setIdentityM(contentRotationTemp, 0)
            contentRotationTemp[5] = kotlin.math.cos(rad).toFloat()
            contentRotationTemp[7] = kotlin.math.sin(rad).toFloat() / d
            // Compose: result = Y * X (X applied first to vertices, then Y).
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
                // Reset so the per-frame startup heartbeat fires on every (re)attach/resume, not just
                // the first session — resume is a common spot for camera/GL init stalls.
                frameCount = 0
                resetCameraStreamWatchdog()
                displayRotationHelper.onResume()
                // Display geometry is per-session state. This session is new, so it has never been told
                // the viewport — and onSurfaceChanged won't fire again for a surface that already
                // exists (AR re-entry, resume, or a rebuild onto a fallback camera config). Force the
                // geometry to be re-pushed on the next frame and drop the cached camera UV transform,
                // which belongs to the previous session. Without both, the background quad gets a
                // transform derived from ARCore's default geometry and the camera renders magnified to
                // the point of showing a single pixel across the whole screen.
                displayRotationHelper.markGeometryDirty()
                backgroundRenderer.invalidateDisplayGeometry()
                if (isSurfaceCreated) {
                    session.setCameraTextureName(backgroundRenderer.textureId)
                }

                // Apply queued flashlight state immediately upon attachment (sessionLock is held)
                flashDirty = false
                applyFlashlightStateLocked(session)

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
        // Re-arm the one-shot screen fit. This call OVERWRITES whatever the fit last set with a 10 m
        // placeholder; without re-arming, the fit never restores a real size, the published design
        // footprint stays that placeholder, and Φ swallows every feature in view — reporting zero
        // backbone, and repartitioning the live fingerprint into one that can never relocalize.
        quadInitialFitApplied = false
    }

    /**
     * Request a flashlight state change. ONLY sets flags: the actual ARCore configure() is applied
     * on the GL thread at the top of the next frame (or in [attachSession], which holds the lock).
     * Calling configure() directly from here — the main thread — raced session.update() on the GL
     * thread; ARCore's Session is not thread-safe and that race corrupted its perception pipeline
     * (native SIGSEGV on ARCore's MTC_vio thread).
     */
    fun updateFlashlight(isOn: Boolean) {
        isFlashlightRequested = isOn
        flashDirty = true
    }

    /**
     * Applies the requested flash mode via Session.configure(). MUST be called with [sessionLock]
     * held (GL frame body or [attachSession]) so configure() is serialized against update().
     */
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
            // ARCore rejects TORCH when the device has no flash unit or the selected camera config
            // can't drive it. This used to be logged and dropped, so the rail's torch button latched
            // on while the light stayed off and the artist had no way to know the camera — not their
            // technique — was why a dark wall wouldn't read. Surface it once per request instead.
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
        // The GL surface is (re)born here, so recompute the camera UV transform once the follow-up
        // onSurfaceChanged has pushed the viewport, rather than reusing UVs cached before the pause.
        backgroundRenderer.invalidateDisplayGeometry()
        onDiag("surface: bg prog=${backgroundRenderer.isProgramReady} shader=${backgroundRenderer.shaderLog} tex=${backgroundRenderer.textureId}")

        // SLAM GL init is isolated: if it throws it must NOT kill the GL thread (that would leave the
        // camera passthrough permanently black). resetGlContext() already (re)builds every GL object,
        // so a separate initGl() call here would be redundant. The breadcrumbs localize a hang (stuck on
        // "slam begin") vs a throw ("slam FAILED"); the native MobileGS::initGl logs split voxel vs mesh.
        try {
            onDiag("surface: voxel program begin")
            slamManager.initVoxelGlProgram()
            onDiag("surface: voxel program ok")
            slamManager.initVoxelGlBuffer()
            onDiag("surface: voxel buffer ok")
            slamManager.initMeshGl()
            onDiag("surface: mesh ok")
        } catch (t: Throwable) {
            Timber.e(t, "ARDIAG slam GL init failed")
            onDiag("surface: slam FAILED ${t.javaClass.simpleName}: ${t.message}")
        }

        overlayRenderer.createOnGlThread()
        pointCloudRenderer.createOnGlThread(context)
        planeRenderer.createOnGlThread(context)
        arDebugRenderer.createOnGlThread(context)
        try {
            perceptionFbo.createOnGlThread(context)
        } catch (t: Throwable) {
            Timber.e(t, "ARDIAG perception FBO init failed — perception will draw un-throttled")
            onDiag("surface: perceptionFbo FAILED ${t.javaClass.simpleName}")
        }
        isSurfaceCreated = true
        onDiag("surface: done")

        Timber.i("ARDIAG onSurfaceCreated: bgTexture=${backgroundRenderer.textureId}")

        sessionLock.withLock {
            session?.setCameraTextureName(backgroundRenderer.textureId)
        }

        startStallWatchdog()
    }

    /** Watches the GL render thread from the side. If onDrawFrame stops advancing (e.g. blocked in
     *  session.update() because the camera never feeds ARCore), the blocked thread can't report, so
     *  this one surfaces the stuck step on screen exactly once. */
    private fun startStallWatchdog() {
        if (watchdog != null) return
        lastTickMs = android.os.SystemClock.elapsedRealtime()
        watchdog = Thread {
            while (!isDestroying) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                val tick = lastTickMs
                if (tick == 0L || isDestroying) continue
                val age = android.os.SystemClock.elapsedRealtime() - tick
                if (age > 2500 && !stallReported && !camStreamReported) {
                    stallReported = true
                    // lastStep now tracks every stage of onDrawFrame (not just up to "update"), so
                    // this names the actual wedged stage: "update" = ARCore blocked waiting on the
                    // camera; "slamCamera"/"slamFeed"/"mesh" = a native SLAM call; "frameDone" =
                    // the frame finished and the GL thread never came back (swap/pause/scheduler).
                    onDiag("RENDER STALLED f=$frameCount step=$lastStep for ${age}ms (no camera frame ever arrived)")
                    reportCameraNotFeeding()
                }
            }
        }.apply { isDaemon = true; name = "ArStallWatchdog"; start() }
    }

    /** Fire the camera-not-feeding self-heal hook at most once. Called from the side watchdog thread
     *  (GL thread blocked in update()) or the draw thread's "no frame after Nf" check — both mean the
     *  selected camera config isn't streaming into ARCore. */
    private fun reportCameraNotFeeding() {
        if (cameraNotFeedingReported) return
        cameraNotFeedingReported = true
        onCameraNotFeeding?.invoke()
    }

    /** Clears the camera-streaming/stall verdict so a freshly attached or reconfigured session earns a
     *  fresh on-screen verdict and the self-heal hook re-arms for the new camera config. */
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

    /**
     * Draw every world-locked perception layer in one pass: coverage mask, scan-time plane grids, and
     * the diagnostic debug layers (feature points, plane grids, point cloud, voxel splats, mesh).
     * Called either into the throttled FBO (normal path) or directly every frame (FBO-unavailable
     * fallback). Mirrors the gating each layer had when drawn inline.
     */
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
        // Coverage pass (VOXEL_HASH scanning): its alpha is the reveal mask the composite uses to
        // un-dim the full-colour camera over mapped regions (see PerceptionFbo.composite reveal mode).
        if (voxelRevealMaskActive) {
            slamManager.drawCoverage()
        }
        // Scan/world-mapping indicator: detected planes as metric grids on the real surfaces.
        if (scanActive && !hideVisualization && camera.trackingState == TrackingState.TRACKING) {
            planeRenderer.drawPlanes(activeSession, viewMatrix, projMatrix, camera.pose, gridMode = true)
        }
        // Diagnostic perception view: ALL active layers of what the AR is seeing.
        // showVoxels/showMesh are NOT consulted: the voxel/splat map and SurfaceMesh were deleted, so
        // neither draws anything, and including them here meant two toggles that render nothing could
        // still switch on the container for the three that do. Only the layers that actually draw
        // decide whether the perception view is worth composing.
        val anyLayerOn = showFeaturePoints || showPlaneGrids || showPoints
        // Show the perception mask (feature points / voxels) on the LIVE camera during scanning —
        // including while aiming a target. The captured frame is the raw camera image (GL overlays
        // aren't baked in), so the mask belongs here, not painted onto the frozen target preview.
        // The confidence-tinted voxel cloud must NOT be drawn over the artwork — it would occlude
        // the very thing the artist is here to see. anchorEstablished is the authoritative gate:
        // once a target exists the map is hidden (the artist is painting), EXCEPT while actively
        // re-aligning to a plane, when seeing the map helps. We deliberately key off anchorEstablished
        // rather than the shared hideVisualization flag, which freeze/export/user-settings also toggle
        // and would otherwise flip the cloud back on mid-session. hideVisualization still suppresses it
        // on demand (freeze preview, export capture, manual setting). Processing keeps running
        // regardless; only the draw is gated.
        if (anyLayerOn && isTracking && (!anchorEstablished || isInPlaneRealignment) && !hideVisualization) {
            if (showFeaturePoints) {
                try {
                    frame.acquirePointCloud().use { arDebugRenderer.update(it) }
                } catch (_: Exception) {
                    // NotYetAvailable/DeadlineExceeded — draw the last uploaded cloud instead.
                }
                arDebugRenderer.draw(viewMatrix, projMatrix)
            }
            if (showPlaneGrids) {
                planeRenderer.drawPlanes(activeSession, viewMatrix, projMatrix, camera.pose, gridMode = true)
            }
            if (showPoints) {
                pointCloudRenderer.draw(viewMatrix, projMatrix)
            }
            // A1 (voxel-map removal): voxel/mesh debug draw retired — the dense map is being removed
            // (A3 deletes the native subsystem). ARCore-based perception layers above (feature points,
            // plane grids, accumulated cloud points) remain as the cheap "what am I seeing" indicators.
            if (frameCount % 120 == 0) {
                // Decides "no data" vs "drawn but invisible" from the diag log alone.
                val planeCount = activeSession.getAllTrackables(com.google.ar.core.Plane::class.java)
                    .count { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
                onDiag("debugView: feat=${arDebugRenderer.lastPointCount} planes=$planeCount pts=${pointCloudRenderer.accumulatedPointCount} fps=${effectivePerceptionFps()}")
            }
        }
    }

    /** Perception redraw rate: full normally, floored while any enabled throttle trigger is active. */
    /**
     * `[tx, ty, tz, qx, qy, qz, qw]` for an ARCore pose, or empty if it throws.
     *
     * Flattened rather than stored as a Pose because this crosses into a serializable model: a Pose
     * is an ARCore handle, and persisting one would tie a saved project to the SDK's object graph.
     */
    private fun poseToList(pose: com.google.ar.core.Pose?): List<Float> = try {
        if (pose == null) emptyList() else listOf(
            pose.tx(), pose.ty(), pose.tz(),
            pose.qx(), pose.qy(), pose.qz(), pose.qw(),
        )
    } catch (e: Exception) {
        Timber.w(e, "pose flatten failed")
        emptyList()
    }

    /**
     * Whether system auto-rotate is on.
     *
     * Recorded at capture because it is the one setting that explains a `rotationNeeded` which
     * disagrees with the physical attitude: `screenOrientation="fullUser"` honours it, so with
     * auto-rotate off the window orientation stops following the device entirely. It is also the
     * precondition for EVALUATION.md E0b — with it off, rotating the phone does not move the
     * experiment's independent variable at all.
     */
    private fun isAutoRotateEnabled(): Boolean = try {
        android.provider.Settings.System.getInt(
            context.contentResolver,
            android.provider.Settings.System.ACCELEROMETER_ROTATION,
        ) == 1
    } catch (e: Exception) {
        false
    }

    private fun effectivePerceptionFps(): Int {
        val laggy = lagThrottleEnabled && perceptionRefreshAvgMs > PERCEPTION_LAG_MS
        return if (systemThrottle || laggy) PERCEPTION_FLOOR_FPS else PERCEPTION_FULL_FPS
    }

    /** True when the camera pose moved enough since the last perception refresh to warrant a redraw. */
    private fun perceptionPoseChanged(view: FloatArray): Boolean {
        var maxDelta = 0f
        for (i in 0 until 16) {
            val d = kotlin.math.abs(view[i] - lastPerceptionView[i])
            if (d > maxDelta) maxDelta = d
        }
        return maxDelta > PERCEPTION_POSE_EPSILON
    }

    /** True when the camera pose moved past the (coarser) idle threshold since the last heavy frame. */
    private fun idlePoseChanged(view: FloatArray): Boolean {
        var maxDelta = 0f
        for (i in 0 until 16) {
            val d = kotlin.math.abs(view[i] - idlePose[i])
            if (d > maxDelta) maxDelta = d
        }
        return maxDelta > IDLE_POSE_EPSILON
    }

    /**
     * Decides whether to run the heavy native SLAM/VIO work this frame, updating [isIdle]. Returns
     * true on a full (heavy) frame; false on a gated idle frame. The phone is "idle" only when the
     * anchor is established, no scan/capture/realign/gesture is active, tracking is healthy, and the
     * pose has been still past the enter-debounce. Exit from idle is instant (first motion frame),
     * with a brief full-rate hold so it can't oscillate at the threshold. While idle, heavy work runs
     * at [idleRateCeilingFps]; while active it's uncapped unless [activeRateCeilingFps] > 0.
     */
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
            !adaptiveRateEnabled -> 0   // master off → always full rate (no idle or battery capping)
            isIdle -> idleRateCeilingFps.coerceAtLeast(1)
            activeRateCeilingFps > 0 -> activeRateCeilingFps
            else -> 0
        }
        val heavy = targetFps <= 0 || (now - lastHeavyWorkMs) >= (1000L / targetFps)
        if (heavy) {
            lastHeavyWorkMs = now
            // Re-baseline the idle pose only on heavy frames, so slow drift accumulates across gated
            // frames and eventually trips idlePoseChanged() rather than being silently tracked away.
            System.arraycopy(view, 0, idlePose, 0, 16)
            haveIdlePose = true
        }
        return heavy
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        // Mode-exit was requested: stop driving ARCore so teardown is effectively
        // instantaneous and we never touch a session that is being closed.
        if (isDestroying) return
        frameCount++
        // Latch the export request HERE, at the top of the frame, not at the readback site near the
        // end. ArViewModel.requestExport sets hideVisualization before exportRequested, so observing
        // the request at frame start guarantees the suppression is already in effect for everything
        // this frame draws. Reading the flag at the end instead meant a request landing mid-frame —
        // after the perception layers had already been drawn with suppression off — was still served
        // by that same frame's readback, baking feature points / plane grids into the exported image.
        // Only latched here; consumed at the export block below, so a frame that bails early (no
        // session yet) still leaves the request pending for the next one, as before.
        val exportThisFrame = exportRequested
        lastTickMs = android.os.SystemClock.elapsedRealtime()
        lastStep = "lock"

        sessionLock.withLock {
            val activeSession = session
            if (activeSession == null) {
                if (frameCount == 1 || frameCount % 120 == 0) {
                    Timber.w("ARDIAG onDrawFrame: session null -> camera black")
                    onDiag("render: f=$frameCount session null -> black")
                }
                return
            }

            lastStep = "setTex"
            activeSession.setCameraTextureName(backgroundRenderer.textureId)
            if (flashDirty) {
                flashDirty = false
                lastStep = "flash"
                applyFlashlightStateLocked(activeSession)
            }
            lastStep = "displayGeom"
            displayRotationHelper.updateSessionIfNeeded(activeSession)

            lastStep = "update"
            val frame: Frame = try {
                activeSession.update()
            } catch (e: SessionPausedException) {
                if (frameCount % 120 == 0) {
                    Timber.w("ARDIAG onDrawFrame: SessionPaused -> camera black")
                    onDiag("render: session paused -> black")
                }
                return
            } catch (e: Exception) {
                Timber.e(e, "ARDIAG ARCore session update failed -> camera black")
                if (frameCount % 120 == 0) onDiag("render: update() failed: ${e.javaClass.simpleName} -> black")
                return
            }

            latestFrame.set(frame)
            lastStep = "hitTests"
            drainHitTestQueue(frame)
            // Camera-streaming verdict, surfaced on-screen so we don't need adb. ARCore returns ts=0
            // until its first camera image lands; once ts>0 the camera IS streaming into the texture.
            val ts = frame.timestamp
            if (ts > 0L && !camStreamReported) {
                camStreamReported = true
                onDiag("CAMERA STREAMING f=$frameCount ts=$ts track=${frame.camera.trackingState}")
            } else if (ts == 0L && !camStreamReported && !camStallWarned &&
                camWaitStartMs > 0L &&
                android.os.SystemClock.elapsedRealtime() - camWaitStartMs > 8000L
            ) {
                // 8s of wall time with no camera image: the device's camera pipeline never started
                // feeding ARCore (resume() succeeded but no frames) — distinct from a slow first
                // frame (this device routinely takes ~3s) and from a slow converge.
                camStallWarned = true
                onDiag("CAMERA STALL: no frame after ${frameCount}f / ${android.os.SystemClock.elapsedRealtime() - camWaitStartMs}ms (ts still 0) -> camera not streaming")
                reportCameraNotFeeding()
            }
            // During the AMBIENT scan, the camera background renders as a newspaper halftone with full
            // colour bleeding in (like ink) as each yaw sector is mapped — the world-mapping indicator.
            val scanActive = !anchorEstablished && ambientScanEnabled && scanPhase == ScanPhase.AMBIENT
            // Voxel-method reveal-mask: while scanning in VOXEL_HASH, the camera renders full colour
            // but DIMMED, and the voxel coverage pass (composited below as a mask) reveals the world
            // at full brightness/colour only where it has been mapped — so the user literally sees
            // what hasn't been scanned yet (it stays dark). Mutually exclusive with the halftone scan
            // indicator (which stays for the other mural methods).
            // A1 (voxel-map removal): the dense voxel map is being retired — it never fed pose
            // (relocalization rides the fingerprint + PnP), and was scan-reveal visualization only.
            // Forcing the reveal mask off disables drawCoverage() and the camera dimming; the halftone
            // scan indicator takes over for VOXEL_HASH mode (same as the other mural methods). Reversible:
            // restore the !anchorEstablished/VOXEL_HASH predicate to re-enable. Native map processing is
            // untouched here (A3 removes it).
            val voxelRevealMaskActive = false
            if (scanActive) backgroundRenderer.updateScanMask(visitedSectorsMask)
            lastStep = "bgDraw"
            // Always draw the camera in full colour (the B&W path is retired); the dimming + reveal
            // for voxel scanning is done by the perception composite (reveal mask), not the camera shader.
            backgroundRenderer.draw(frame, scanActive && !voxelRevealMaskActive, grayscale = false)
            // Coverage mask is now drawn with the throttled perception layers (see "debugView").
            if (frameCount <= 10 || frameCount % 60 == 0) {
                Timber.i("ARDIAG drawFrame f=$frameCount tracking=${frame.camera.trackingState} anchor=$anchorEstablished ambientScan=$ambientScanEnabled scanActive=$scanActive")
                // On-screen heartbeat. f climbing => render loop alive; ts (camera frame timestamp)
                // changing => ARCore is streaming camera images; track => PAUSED until ARCore converges.
                // f stuck at 1 = loop stalled; f climbs + ts frozen = camera not streaming; f climbs +
                // ts changes + still black = camera is drawing but hidden (z-order/opaque overlay).
                onDiag("render: f=$frameCount track=${frame.camera.trackingState} ts=${frame.timestamp}")
            }

            // Scan/world-mapping indicator: detected planes render as metric grids anchored to the
            // real 3D surfaces (0.25 m cells, local axes emphasised), so the user can read each
            // plane's orientation at a glance. Only while scanning and tracking.
            lastStep = "planes"
            // Scan-time plane grids are now drawn with the throttled perception layers (see "debugView").

            lastStep = "anchorEstablish"
            // First-run doodle demo: once a surface exists and we're tracking, auto-establish the
            // wall anchor (same path as a capture confirm, minus the fingerprint) so the scribble has
            // somewhere to stick. One-shot per doodle session.
            if (doodleLockActive && !anchorEstablished && !pendingAnchorEstablishment &&
                !doodleAutoAnchorRequested && planeDetectedReported &&
                frame.camera.trackingState == TrackingState.TRACKING
            ) {
                doodleAutoAnchorRequested = true
                pendingAnchorEstablishment = true
            }
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

                    val camPose = camera.pose
                    val camPosX = camPose.tx(); val camPosY = camPose.ty(); val camPosZ = camPose.tz()

                    var anchor: com.google.ar.core.Anchor? = null
                    // Surface normal (world space) for laying the artwork flat & facing the user. Filled
                    // below from the chosen surface; left as the anchor→camera direction by default.
                    var nrmX = 0f; var nrmY = 0f; var nrmZ = 0f
                    var haveNormal = false
                    if (chosen != null) {
                        val pose = chosen.hitPose
                        val dx = pose.tx() - camPosX
                        val dy = pose.ty() - camPosY
                        val dz = pose.tz() - camPosZ
                        val dist = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble())
                        if (dist in 0.1..10.0) {
                            pose.toMatrix(anchorModelMatrix, 0) // full surface pose (position + normal)
                            // Anchor to the hit's trackable so ARCore keeps it pinned to the wall as the
                            // artist moves, instead of a free world point at a guessed depth.
                            anchor = chosen.createAnchor()
                            // Capture the surface normal so the overlay can be laid flat & facing the
                            // user. A plane pose's local +Y is the wall normal; a depth/feature point
                            // has no reliable orientation, so use the anchor→camera direction (a surface
                            // the user chose to paint on faces the user).
                            if (chosen.trackable is com.google.ar.core.Plane) {
                                val axis = FloatArray(3)
                                pose.getTransformedAxis(1, 1f, axis, 0) // local +Y = plane normal
                                nrmX = axis[0]; nrmY = axis[1]; nrmZ = axis[2]
                            } else {
                                nrmX = -dx; nrmY = -dy; nrmZ = -dz // toward the camera
                            }
                            haveNormal = true
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
                        // Free/fallback anchor: face the user directly (anchor→camera).
                        nrmX = camPosX - anchorModelMatrix[12]
                        nrmY = camPosY - anchorModelMatrix[13]
                        nrmZ = camPosZ - anchorModelMatrix[14]
                        haveNormal = true
                    }

                    // Orient the normal toward the camera (so the artwork faces the user) and store it
                    // normalized; a degenerate/zero normal leaves anchorSurfaceNormal zeroed, which the
                    // draw path reads as "use the raw anchor frame".
                    if (haveNormal) {
                        val toCamX = camPosX - anchorModelMatrix[12]
                        val toCamY = camPosY - anchorModelMatrix[13]
                        val toCamZ = camPosZ - anchorModelMatrix[14]
                        if (nrmX * toCamX + nrmY * toCamY + nrmZ * toCamZ < 0f) {
                            nrmX = -nrmX; nrmY = -nrmY; nrmZ = -nrmZ
                        }
                        val len = Math.sqrt((nrmX * nrmX + nrmY * nrmY + nrmZ * nrmZ).toDouble()).toFloat()
                        if (len > 1e-4f) {
                            anchorSurfaceNormal[0] = nrmX / len
                            anchorSurfaceNormal[1] = nrmY / len
                            anchorSurfaceNormal[2] = nrmZ / len
                        } else {
                            anchorSurfaceNormal[0] = 0f; anchorSurfaceNormal[1] = 0f; anchorSurfaceNormal[2] = 0f
                        }
                    } else {
                        anchorSurfaceNormal[0] = 0f; anchorSurfaceNormal[1] = 0f; anchorSurfaceNormal[2] = 0f
                    }
                    slamManager.updateAnchorTransform(anchorModelMatrix)
                    // Doodle demo: publish the wall plane (anchor point + surface normal) so the
                    // ViewModel can build a fingerprint from the drawing and relocalize against it.
                    if (doodleLockActive) {
                        doodleWallPlane = floatArrayOf(
                            anchorModelMatrix[12], anchorModelMatrix[13], anchorModelMatrix[14],
                            anchorSurfaceNormal[0], anchorSurfaceNormal[1], anchorSurfaceNormal[2],
                        )
                    }
                    setPrimaryAnchor(anchor) // non-null: the fallback branch always creates one
                    anchorEstablished = true
                    // Announce it beyond the GL thread. This is the ONLY anchor write that counts as
                    // establishment — the plane refiner and the depth fallback both write poses
                    // before this point, in a different frame convention, and a capture that
                    // resolved against one of those would be co-registered ~90° out.
                    slamManager.markAnchorEstablished()
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

            val viewMatrix = viewMatrixScratch
            val projMatrix = projMatrixScratch
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f)

            val mappingViewMatrix = mappingViewMatrixScratch
            val mappingProjMatrix = mappingProjMatrixScratch
            // mappingProjMatrix is only partially overwritten below (cells 0/5/8/9/10/11/14/15), so
            // a reused buffer has to be cleared or it would carry the previous frame's stray cells.
            java.util.Arrays.fill(mappingProjMatrix, 0f)
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

            lastStep = "slamCamera"
            val isTracking = camera.trackingState == TrackingState.TRACKING

            // First-run onboarding: report the first tracking plane exactly once. getAllTrackables is
            // not free, so only poll (throttled) until we've reported, then this is a single boolean
            // check per frame forever after.
            if (!planeDetectedReported && isTracking && frameCount % 30 == 0) {
                val hasPlane = activeSession.getAllTrackables(com.google.ar.core.Plane::class.java)
                    .any { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
                if (hasPlane) {
                    planeDetectedReported = true
                    onPlaneDetected()
                }
            }

            // Adaptive idle gating: skip the heavy native SLAM/VIO map integration on gated idle
            // frames (projection locked + phone still). session.update() above still ran, so ARCore's
            // pose keeps advancing and the first heavy frame after resume re-feeds a correct pose.
            // The not-tracking case is never gated (shouldRunHeavyThisFrame forces active), so a
            // relocalization always re-feeds SLAM immediately.
            if (shouldRunHeavyThisFrame(viewMatrix, isTracking)) {
                slamManager.updateCamera(viewMatrix, projMatrix, mappingViewMatrix, mappingProjMatrix, frame.timestamp)
            }

            lastStep = "light"
            val lightEstimate = frame.lightEstimate
            if (lightEstimate.state == com.google.ar.core.LightEstimate.State.VALID) {
                onLightUpdated(lightEstimate.pixelIntensity)
            }
            // Re-arm the hard cold-snap whenever ARCore isn't tracking (pocket / screen-off / loss), so
            // the next confident relocalization snaps the overlay back instantly rather than easing in.
            if (!isTracking) poseFusion.markRelocalizing()
            val depthSupported = depthApiEnabled && activeSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)

            val yawRad = kotlin.math.atan2(-viewMatrix[2].toDouble(), -viewMatrix[10].toDouble())
            val yawDeg = Math.toDegrees(yawRad).toFloat()

            // (The world-mapping indicator is now the camera-background "ink develop" reveal, applied
            // in BackgroundRenderer.draw(frame, scanActive) above — no separate overlay here.)

            slamManager.setArCoreTrackingState(isTracking)

            lastStep = "anchorCandidates"
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

            
            // --- Democratic Consensus Transformation + smoothed reloc fusion ---
            // Backbone: ARCore consensus once anchored, else the native cached pose (as before).
            lastStep = "consensus"
            val backbone = backboneScratch
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
                    // THE teleological input, finally connected. The claim in TELEOLOGICAL_SLAM.md is
                    // that the further along the painting is, the more real-world corroboration the
                    // engine has and the harder the overlay locks. That third stage was never wired:
                    // this argument was pinned at 1f, so painting progress reached the HUD and nothing
                    // else, and correction strength was identical at 0% and 100% painted.
                    //
                    // It now carries the measured corroboration (fraction of the registered artwork's
                    // features the real wall currently answers for). PoseFusion floors it at
                    // CONF_FLOOR, so an unpainted wall still corrects at half strength on the inlier
                    // ratio alone — the previous behaviour is the floor, not the ceiling — and a
                    // well-advanced mural earns up to 2x that.
                    //
                    // Corroboration CONFIDENCE, not painting PROGRESS. Progress answers "how much of
                    // the mural exists" on a timescale of hours; this answers "how much do I trust
                    // this frame" on a timescale of frames. They were one scalar, so a momentary
                    // tracking hiccup decayed the progress reading and went on suppressing correction
                    // strength for seconds after the wall came back into view.
                    //
                    // Negative means no corroboration attempt has produced a measurement yet — a
                    // different state from "measured, found nothing". Map it to 0f so PoseFusion
                    // falls back to CONF_FLOOR and corrects on the inlier ratio alone, rather than
                    // feeding a negative through a parameter documented as [0,1].
                    confGlobal = slamManager.getCorroborationConfidence().coerceAtLeast(0f),
                )
            } else backbone

            // First-run doodle demo: feed the anchor's world translation to the lock tracker while the
            // overlay holds. Once it has held steady past the min-draw dwell, fire the swap once.
            if (doodleLockActive && anchorEstablished && !doodleLockReported) {
                // anchorMatrix is the FUSED pose — once a fingerprint has been built from the user's
                // drawing, poseFusion folds the mark-PnP relocalization correction into it, so the
                // tracker is measuring relocalization stability, not raw ARCore anchor. Gate the swap
                // on BOTH a still fused pose AND a confident relock (PnP inliers), so the lock only
                // fires when the teleological SLAM has genuinely latched onto the drawn marks. On a
                // blank wall relocalization stays weak until the user draws, so this self-times.
                anchorLockTracker.update(anchorMatrix[12], anchorMatrix[13], anchorMatrix[14])
                val relocInliers = slamManager.getRelocResult()[16].toInt()
                if (anchorLockTracker.locked && relocInliers >= DOODLE_MIN_RELOC_INLIERS) {
                    doodleLockReported = true
                    onDoodleLocked()
                }
            }

            // Throttle UI and distance updates to 15Hz to match SLAM processing frequency and reduce state churn.
            lastStep = "uiTick"
            if (frameCount % 4 == 0) {
                driftCostProbe?.let { probe ->
                    val stageMs = slamManager.getStageTimings()
                    // Truth = the mark-PnP pose, available only when relocalization actually
                    // published one this cycle.
                    //
                    // This gate used to be `getVisibleConfidenceAvg() > markVisibleConf` (0.5f).
                    // getConfidenceAvgs returns a HARDCODED 0.0 — "voxel/splat map deleted" — so the
                    // comparison was `0.0 > 0.5`, always false. truthPose was therefore always null,
                    // and EVERY eval CSV row carried errMm = -1 and marksVisible = false. The
                    // harness's headline metric could not be populated at all, which makes every
                    // errMm comparison in EVALUATION.md (E2's baseline, E3, E4's screening, E10,
                    // E11, E12) unrunnable rather than merely noisy. All of Phase 6a's telemetry fed
                    // a file whose primary column was structurally absent.
                    //
                    // RelocDiagnostics is the live signal: reject == OK means solvePnPRansac
                    // published a pose this cycle, and the inlier count says how well. Read once
                    // here and reused for the reloc columns below, so the row's truth flag and its
                    // diagnostics describe the same relocalization rather than two samples.
                    val relocDiag = slamManager.getRelocDiagnostics()
                    val marksVisible = relocDiag.reject == com.hereliesaz.graffitixr.common.model
                        .RelocReject.OK && relocDiag.inliers >= MIN_TRUTH_INLIERS
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
                        // Why the last attempt did not publish, logged per row. EVALUATION.md §6:
                        // a configuration whose failures are NO_FEATURES needs different work from
                        // one whose failures are FEW_INLIERS, and the aggregate error number cannot
                        // tell them apart. Cost: one JNI transition, six relaxed atomic loads, and
                        // two short-lived allocations (the jintArray and the RelocDiagnostics). Not
                        // free — but this whole block is inside `driftCostProbe?.let`, so it does
                        // not exist outside an eval run, and the block already allocates two
                        // FloatArrays per tick. (An earlier comment here said "four atomics", which
                        // was neither the count nor the mechanism.)
                        reloc = relocDiag,
                        // E0b's independent variable is the rotation that was in force AT CAPTURE,
                        // because that is the one baked into the fingerprint's 3D points. Sampling
                        // the live rotation here instead — which an earlier version of this call
                        // did — files a portrait-captured, landscape-relocalized run under 0, the
                        // control condition, and turns a real effect into a null result.
                        captureRotationNeededDeg = lastCaptureRotationNeededDeg,
                        // The live rotation is still logged, as a secondary signal: the reloc feed
                        // has frame handling of its own, so rows where the two disagree are the
                        // ones worth a second look.
                        liveRotationNeededDeg =
                            (sensorOrientation - displayRotationHelper.getRotation() * 90 + 360) % 360,
                    )
                }

                // Read depth on the GL thread — Frame.acquireDepthImage16Bits() is only valid during
                // onDrawFrame and races session.update() if touched from backgroundScope. Compute the
                // smoothed value here and hand the snapshot to the background readout below.
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
                        }
                    }
                } catch (e: Exception) { /* ignore */ }
                // Snapshot the smoothed value (kept across invalid/dropped frames) for the readout.
                val centerDepth = smoothedCenterDepth
                // The view matrix is GL-thread scratch that the next frame overwrites in place, so
                // the coroutine below gets its own copy rather than a live reference.
                val viewMatrixSnapshot = viewMatrix.copyOf()

                backgroundScope.launch {
                    // Both modes report the accumulated ARCore point cloud. MURAL used to read
                    // slamManager.getSplatCount(), which is `return 0;` since the voxel/splat map was
                    // deleted — so in MURAL, the DEFAULT mode, splatCount was structurally zero and
                    // four separate decisions silently degraded:
                    //
                    //   * co-op hosting refused outright (`splatCount <= 0` gate) no matter how long
                    //     the artist scanned;
                    //   * the WALL scan hint pinned to "build the map" forever (`< 5000`), with the
                    //     "closer" and "higher/lower" hints unreachable;
                    //   * WALL -> COMPLETE reachable only via the anchor, never via map coverage;
                    //   * the stereo-stuck recovery trigger vacuously true, since it keys on
                    //     `splatCount == 0 && MURAL`.
                    //
                    // Reading a hardcoded 0 is never the right answer, and the point cloud is now the
                    // only live map, so both modes read it. That also restores the stereo check to a
                    // real condition rather than a constant.
                    val (count, immutableCount) = pointCloudRenderer.accumulatedPointCount to 0

                    val visConf = slamManager.getVisibleConfidenceAvg()
                    val globConf = slamManager.getGlobalConfidenceAvg()

                    var relDir: Triple<Float, Float, Float>? = null
                    val distanceMeters = run {
                        if (!anchorEstablished) return@run -1f
                        val camPose = FloatArray(16)
                        android.opengl.Matrix.invertM(camPose, 0, viewMatrixSnapshot, 0)
                        val dx = anchorMatrix[12] - camPose[12]
                        val dy = anchorMatrix[13] - camPose[13]
                        val dz = anchorMatrix[14] - camPose[14]
                        val len = kotlin.math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()

                        if (len > 0.01f) {
                            // android.opengl.Matrix is column-major: index [col * 4 + row]. Rotating
                            // a world-space delta into view space is R · delta, where R takes rows
                            // from R (columns of the view matrix's 3×3 rotation), i.e. strides of 4,
                            // not 1. The old stride-1 indexing computed R^T · delta — correct only
                            // when the camera is world-aligned; as soon as the phone yaws it maps
                            // the target vector onto the wrong axis and the indicator points off.
                            val localX = dx * viewMatrix[0] + dy * viewMatrix[4] + dz * viewMatrix[8]
                            val localY = dx * viewMatrix[1] + dy * viewMatrix[5] + dz * viewMatrix[9]
                            val localZ = dx * viewMatrix[2] + dy * viewMatrix[6] + dz * viewMatrix[10]
                            relDir = Triple(localX / len, localY / len, localZ / len)
                        }

                        // Forward is -Z in the view frame; the third row of R (view-matrix column 2)
                        // is the world-space direction the camera looks along, so dot(delta, -row2)
                        // is positive iff the anchor is in front of the camera. Same column-major
                        // stride fix as above.
                        val fwdDot = dx * (-viewMatrix[2]) + dy * (-viewMatrix[6]) + dz * (-viewMatrix[10])
                        if (len > 0.01f && fwdDot > 0f) len else -1f
                    }

                    onTrackingUpdated(isTracking, count, immutableCount, depthSupported, yawDeg, distanceMeters, relDir, isDualLens, isDualLensHardware, centerDepth, visConf, globConf)
                }
            }

            lastStep = "capture"
            if (captureRequested) {
                captureRequested = false
                try {
                    frame.acquireCameraImage().use { image ->
                        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                        // Real native YUV→RGBA (OpenCV NEON on ARM). Replaces the fake "direct"
                        // path that went via YuvImage.compressToJpeg + BitmapFactory.decodeByteArray
                        // and cost 100–300 ms per capture.
                        YuvConverter.yuvToRgbaBitmap(image, bitmap)

                        val displayDegrees = displayRotationHelper.getRotation() * 90
                        val rotationNeeded = (sensorOrientation - displayDegrees + 360) % 360
                        // Recorded HERE, where the capture's geometry is decided, because this is
                        // the angle that gets baked into the fingerprint: backProject runs once
                        // per target inside MetricFingerprintBuilder.buildSingle, against these rotated
                        // intrinsics and a view matrix now rotated to match (Phase 0). However the device
                        // is held afterwards, the skew in those 3D points does not change.
                        lastCaptureRotationNeededDeg = rotationNeeded

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

                        val fx = intrinsics.focalLength[0]
                        val fy = intrinsics.focalLength[1]
                        val cx = intrinsics.principalPoint[0]
                        val cy = intrinsics.principalPoint[1]
                        val dims = intrinsics.imageDimensions
                        val rawW = dims[0].toFloat()
                        val rawH = dims[1].toFloat()

                        // Extracted to CaptureRotation so the convention is testable AT ITS ORIGIN.
                        // Phase 0's correctness argument is that this transform, paired with the
                        // bitmap rotation, turns a sensor ray into R_z(+rotationNeeded) * itself —
                        // and while it lived inline here nothing could drive a pixel through it, so
                        // the derivation and the tests both rested on one reading of these lines.
                        val intrArr = com.hereliesaz.graffitixr.feature.ar.anchor.CaptureRotation
                            .rotateIntrinsics(fx, fy, cx, cy, rawW, rawH, rotationNeeded)

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

                        // Single-capture target creation needs the metric wall plane being aimed at.
                        //
                        // This used to accept ONLY a plane classifying as MATCH (green: near-parallel
                        // and within 3 m). But MATCH is a judgement about how good the VIEW is, not
                        // about whether ARCore has solved the plane — a SUBOPTIMAL plane's centerPose
                        // is exactly as metric. Requiring green meant that on a dim wall, or one taken
                        // at an angle, target creation refused outright with "face a wall", and the
                        // artist had no way to proceed. Now any tracking plane under the tap will do,
                        // green preferred; the honest quality bar is downstream, where the fingerprint
                        // needs enough features to actually localize.
                        var wallPlane: FloatArray? = null
                        if (tap != null && surfaceWidth > 0) {
                            try {
                                val px = tap[0] * surfaceWidth
                                val py = tap[1] * surfaceHeight
                                var fallback: com.google.ar.core.Plane? = null
                                for (h in frame.hitTest(px, py)) {
                                    val tr = h.trackable
                                    if (tr !is com.google.ar.core.Plane) continue
                                    if (tr.trackingState != TrackingState.TRACKING) continue
                                    if (tr.subsumedBy != null) continue
                                    if (!tr.isPoseInPolygon(h.hitPose)) continue
                                    if (planeRenderer.classifyPlane(tr, camera.pose) ==
                                            PlaneRenderer.PlaneMatchResult.MATCH) {
                                        fallback = tr
                                        break // green wins outright
                                    }
                                    if (fallback == null) fallback = tr // first non-green hit, kept
                                }
                                fallback?.let { tr ->
                                    val c = tr.centerPose
                                    val nrm = FloatArray(3)
                                    c.getTransformedAxis(1, 1.0f, nrm, 0) // plane local +Y = normal
                                    wallPlane = floatArrayOf(c.tx(), c.ty(), c.tz(), nrm[0], nrm[1], nrm[2])
                                }
                            } catch (e: Exception) {
                                Timber.w(e, "Wall-plane hit-test for capture failed")
                            }
                        }

                        // Everything knowable about how and where the device was held, sampled at
                        // the instant the geometry is frozen. backProject runs once per target, so
                        // this is the only moment these conditions exist to be recorded — afterwards
                        // the fingerprint carries their consequences with no record of their cause.
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

                        onTargetCaptured(
                            bitmap, image.width, image.height,
                            depthBuffer,
                            depthWidth, depthHeight, depthStride,
                            intrArr, mappingViewMatrix.copyOf(),
                            rotationNeeded, tapDistanceMeters,
                            wallPlane, captureEnvironment,
                        )
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to capture target frame")
                }
            }

            lastStep = "export"
            if (exportThisFrame) {
                exportRequested = false
                try {
                    // Read the composited GL framebuffer instead of the raw camera image. By this
                    // point in onDrawFrame the camera texture and the wall-anchored overlay quad
                    // (with its true perspective/lean) are already drawn, so the readback matches
                    // exactly what the user sees on-screen minus the Compose UI overlays (rail,
                    // settings, reticle chips, distance labels) — those live in a separate Compose
                    // window that never touches this framebuffer, so they naturally aren't
                    // captured. Camera-sensor rotation is baked in by ARCore's camera-texture draw
                    // (background renderer applies the display transform), so no post-rotate is
                    // needed here — unlike the raw-image path that had to correct sensor orientation.
                    // Snapshot the callback so a concurrent clear doesn't strand the readback
                    // in a bitmap nobody owns. Also short-circuits the whole allocate/draw block
                    // if nothing is listening — treat requestExport being unset here as a spurious
                    // flag flip rather than doing work for nothing.
                    val callback = onExportCaptured
                    val w = surfaceWidth
                    val h = surfaceHeight
                    if (callback != null && w > 0 && h > 0) {
                        val buf = ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder())
                        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
                        buf.rewind()
                        val flipped = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        // GL origin is bottom-left; Bitmap is top-left. Wrap the readback in an
                        // upside-down source Bitmap, then draw it into `flipped` with a vertical
                        // scale of -1 so the final Bitmap has natural (top-left) orientation.
                        val source = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        source.copyPixelsFromBuffer(buf)
                        val canvas = android.graphics.Canvas(flipped)
                        val matrix = android.graphics.Matrix().apply { postScale(1f, -1f, w / 2f, h / 2f) }
                        canvas.drawBitmap(source, matrix, null)
                        source.recycle()

                        callback(flipped)
                        onExportCaptured = null
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to capture export frame")
                }
            }

            // ── Frame Data Pipeline ──
            // Post-anchor this was every 30th frame — 2 Hz at 60 fps — which is the rate the wall gets
            // re-checked for relocalization while the artist is actually painting, i.e. the rate the
            // overlay can recover after drift or a look-away. That was set when every feed did a full
            // YUV->RGB convert plus rotate on this thread whether or not anything wanted the result;
            // relocWantsFrame() now rejects that work up front, so a feed the reloc worker isn't ready
            // for costs only the YUV assembly. The worker itself self-limits (200 ms between attempts),
            // so feeding at ~6 Hz keeps it saturated without ever queueing.
            lastStep = "slamFeed"
            val throttleRate = if (anchorEstablished) 10 else 3 // 6Hz vs 20Hz
            if (isTracking && frameCount % throttleRate == 0) {
                // Calculate device motion (linear and angular velocity) for deblurring
                val currentPose = camera.displayOrientedPose
                if (frameCount > 0) {
                    // Delta-based velocity estimation, over the MEASURED interval. This was hardcoded
                    // to 1/20 s "approx 20Hz throttle", which only ever matched the pre-anchor rate —
                    // post-anchor the same divisor was applied to a 2 Hz sample interval, inflating
                    // every reported velocity by 10x. The deblur consumer reads these, so the error
                    // was real, not cosmetic. Clamped so a long first gap can't produce a wild value.
                    val nowNs = frame.timestamp
                    val dt = if (lastMotionSampleNs > 0L && nowNs > lastMotionSampleNs) {
                        ((nowNs - lastMotionSampleNs) / 1_000_000_000.0f).coerceIn(0.005f, 1f)
                    } else {
                        throttleRate / 60f // first sample: assume the nominal 60 fps camera
                    }
                    lastMotionSampleNs = nowNs
                    val linVel = floatArrayOf(
                        (currentPose.tx() - lastPoseX) / dt,
                        (currentPose.ty() - lastPoseY) / dt,
                        (currentPose.tz() - lastPoseZ) / dt
                    )
                    // Angular velocity from the quaternion delta between throttled frames:
                    // q_rel = q_cur * conj(q_prev) -> axis-angle -> (axis * angle / dt).
                    val q = currentPose.rotationQuaternion
                    val prev = lastQuat
                    val angVel = if (prev != null) angularVelocity(prev, q, dt) else floatArrayOf(0f, 0f, 0f)
                    slamManager.updateDeviceMotion(angVel, linVel)
                    lastQuat = q
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
                            // Always accumulate. This count now drives co-op gating, the scan
                            // hints and phase completion, so it cannot hang off `showPoints` — that
                            // is a DRAW toggle, and letting a visualization setting decide whether
                            // the map is built is how turning off a layer silently disables co-op.
                            pointCloudRenderer.update(pointCloud)
                            
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

            // Cull parallax-failed / never-reinforced voxels. A wrong-depth voxel knocked down by
            // the parallax check (-0.1) and not re-confirmed trends below threshold and is removed,
            // so the map converges on geometry verified from multiple baselines. Runs ~every 20s
            // during active mapping; threshold sits under the seed confidences (sparse 0.4, depth
            // 0.5) so a fresh, not-yet-verified voxel is never culled before it gets a chance.
            lastStep = "prune"
            if (isTracking && !anchorEstablished && frameCount % 600 == 0) {
                slamManager.pruneByConfidence(0.3f)
            }

            // Continuous wall-depth refinement: keep the overlay flush with the ARCore plane estimate.
            // Runs at ~1 Hz (every 30 frames) to amortise getAllTrackables() overhead.
            // Only runs BEFORE the anchor is established or during manual realignment to
            // prevent the image from following the camera gaze once locked to a target.
            lastStep = "planeRefine"
            if ((!anchorEstablished || isInPlaneRealignment) && frameCount % 30 == 0) {
                try {
                    refineAnchorFromBestPlane(activeSession, viewMatrix)
                } catch (e: Exception) {
                    // Non-fatal: skip this refinement cycle
                }
            }

            // Depth-based fallback: sample centre-screen depth every 10 frames
            lastStep = "depthFallback"
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

            lastStep = "overlayTex"
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

            lastStep = "mesh"
            // Voxel/splat map retirement: the SURFACE_MESH overlay-warp is dropped — always render the
            // overlay flat on the anchor (the dense map that fed the warp is being neutralized/removed).
            val hasMeshData = false

            lastStep = "overlayDraw"
            // Base overlay frame: ALWAYS lay the artwork FLAT against the selected surface and facing
            // the user, for every anchor type. The quad's surface normal is its local +Z, so build an
            // orthonormal frame at the live anchor position whose +Z = the captured surface normal
            // (already oriented toward the camera). +Y = world-up projected ⟂ to +Z so the artwork is
            // upright; +X = +Y × +Z (= width, horizontal). If the surface is near-horizontal (normal
            // ≈ world-up, e.g. floor/ceiling) world-up is degenerate, so fall back to the camera's up.
            // A zero stored normal (no anchor yet) leaves the raw anchor frame.
            System.arraycopy(anchorMatrix, 0, overlayBaseScratch, 0, 16)
            run {
                // Clear any stale normal on the GL thread once the anchor is gone (reset/rescan), so a
                // later preview/draw can't reuse the previous anchor's orientation. A zero normal makes
                // the block below fall through to the raw anchor frame.
                if (!anchorEstablished) {
                    anchorSurfaceNormal[0] = 0f; anchorSurfaceNormal[1] = 0f; anchorSurfaceNormal[2] = 0f
                }
                val nx = anchorSurfaceNormal[0]; val ny = anchorSurfaceNormal[1]; val nz = anchorSurfaceNormal[2]
                if (nx != 0f || ny != 0f || nz != 0f) {
                    // Pick an up reference that isn't parallel to the normal.
                    var upX = 0f; var upY = 1f; var upZ = 0f
                    if (kotlin.math.abs(ny) > 0.95f) {
                        // Surface ≈ horizontal: world-up ∥ normal is unusable. Use the camera's up
                        // (viewMatrix row 1 is the camera up axis in world space).
                        upX = viewMatrix[1]; upY = viewMatrix[5]; upZ = viewMatrix[9]
                    }
                    // +Y = up projected onto the plane ⟂ to the normal, normalized.
                    val dot = upX * nx + upY * ny + upZ * nz
                    var yX = upX - dot * nx; var yY = upY - dot * ny; var yZ = upZ - dot * nz
                    var yLen = kotlin.math.sqrt(yX * yX + yY * yY + yZ * yZ)
                    if (yLen <= 1e-4f) {
                        // Up reference parallel to the normal (e.g. a level camera edge-on to a
                        // horizontal surface): fall back to the camera's forward axis (viewMatrix row 2)
                        // so the in-plane frame is still well-defined instead of reverting to raw.
                        upX = viewMatrix[2]; upY = viewMatrix[6]; upZ = viewMatrix[10]
                        val dot2 = upX * nx + upY * ny + upZ * nz
                        yX = upX - dot2 * nx; yY = upY - dot2 * ny; yZ = upZ - dot2 * nz
                        yLen = kotlin.math.sqrt(yX * yX + yY * yY + yZ * yZ)
                    }
                    if (yLen > 1e-4f) {
                        yX /= yLen; yY /= yLen; yZ /= yLen
                        // +X = +Y × +Z (right-handed: width axis, horizontal across the surface).
                        val xX = yY * nz - yZ * ny
                        val xY = yZ * nx - yX * nz
                        val xZ = yX * ny - yY * nx
                        overlayBaseScratch[0] = xX; overlayBaseScratch[1] = xY; overlayBaseScratch[2] = xZ;  overlayBaseScratch[3] = 0f
                        overlayBaseScratch[4] = yX; overlayBaseScratch[5] = yY; overlayBaseScratch[6] = yZ;  overlayBaseScratch[7] = 0f
                        overlayBaseScratch[8] = nx; overlayBaseScratch[9] = ny; overlayBaseScratch[10] = nz; overlayBaseScratch[11] = 0f
                        // Translation stays the live anchor position (cols 12-14 already copied above).
                    }
                }
            }

            // Center the overlay on the matched-marks centroid instead of the screen-center anchor.
            // overlayMarkCenterLocal is the centroid in the fingerprint anchor's frame; reconstruct
            // its world position from the live (drift-tracked, reloc-fused) anchor, then project the
            // delta from the anchor onto the overlay-local in-plane axes. Recomputed every frame, so
            // it follows drift and recovers automatically after anchor re-establishment / reload.
            val markLocal = slamManager.overlayMarkCenterLocal
            if (markLocal == null || markLocal.size < 3 || !anchorEstablished) {
                markOffsetX = 0f; markOffsetY = 0f
            } else {
                val cwX = anchorMatrix[0] * markLocal[0] + anchorMatrix[4] * markLocal[1] + anchorMatrix[8] * markLocal[2] + anchorMatrix[12]
                val cwY = anchorMatrix[1] * markLocal[0] + anchorMatrix[5] * markLocal[1] + anchorMatrix[9] * markLocal[2] + anchorMatrix[13]
                val cwZ = anchorMatrix[2] * markLocal[0] + anchorMatrix[6] * markLocal[1] + anchorMatrix[10] * markLocal[2] + anchorMatrix[14]
                val dx = cwX - overlayBaseScratch[12]
                val dy = cwY - overlayBaseScratch[13]
                val dz = cwZ - overlayBaseScratch[14]
                // Project the world delta onto the overlay-local in-plane axes (columns 0 and 1).
                markOffsetX = overlayBaseScratch[0] * dx + overlayBaseScratch[1] * dy + overlayBaseScratch[2] * dz
                markOffsetY = overlayBaseScratch[4] * dx + overlayBaseScratch[5] * dy + overlayBaseScratch[6] * dz
            }

            // Meters-per-pixel at the overlay depth, so the UI can convert a screen drag (px) into an
            // in-plane translation (m). projMatrix[5] = cot(fovY/2), so tan(fovY/2) = 1/projMatrix[5].
            val ovz = viewMatrix[2] * overlayBaseScratch[12] + viewMatrix[6] * overlayBaseScratch[13] +
                viewMatrix[10] * overlayBaseScratch[14] + viewMatrix[14]
            val tanHalfFovY = if (projMatrix[5] != 0f) 1f / projMatrix[5] else 0f
            currentMetersPerPixel =
                if (surfaceHeight > 0) kotlin.math.abs(ovz) * 2f * tanHalfFovY / surfaceHeight else 0f

            // User whole-design transform (AR "Layer" item) + marks-centering offset, in the overlay's
            // local frame: translate along the plane, Z-rotate (in-plane spin), and scale.
            android.opengl.Matrix.setIdentityM(overlayLocalScratch, 0)
            android.opengl.Matrix.translateM(
                overlayLocalScratch, 0, markOffsetX + overlayPanX, markOffsetY + overlayPanY, 0f
            )
            // Spin the artwork in its own plane (Z-axis rotation about the wall normal).
            android.opengl.Matrix.rotateM(overlayLocalScratch, 0, overlayRotationDeg, 0f, 0f, 1f)
            // Snapshot the design's frame BEFORE the scale goes in (IMPLEMENTATION.md 2.3/2.4). Φ
            // needs a rigid matrix with the scale folded into the extents instead: the composed one
            // below is scaled (s, s, 1), which is NOT the uniform similarity an inverse-with-scale
            // would assume, and inverting it as one is wrong on Z. Taken here rather than
            // reconstructed at capture so it cannot drift from the transform actually drawn.
            android.opengl.Matrix.multiplyMM(
                overlayRigidScratch, 0, overlayBaseScratch, 0, overlayLocalScratch, 0
            )
            val newHalfW = overlayRenderer.extentHalfW * overlayScale
            val newHalfH = overlayRenderer.extentHalfH * overlayScale
            // `quadInitialFitApplied` is part of the precondition, not an optimisation. Until the
            // screen-fit above has run, the quad's extents are still QUAD_HALF_EXTENT — a 10 m
            // square placeholder chosen so artwork is never spatially confined. Publishing that as
            // the design's size would make Φ swallow every feature in view and report zero backbone
            // — "the artwork covers the whole wall" on a design the artist has only just opened.
            val placed = anchorEstablished && overlayRenderer.hasTexture && quadInitialFitApplied
            // Size OR pose. Φ asks where a feature sits relative to the artwork, and panning or
            // spinning the design changes that answer exactly as much as resizing it does — the
            // artist drags the artwork across the wall far more often than they pinch it. Watching
            // the extents alone left every one of those moves silently unpartitioned, against a
            // footprint the artwork had left.
            // Size AND placement, in one detector. Φ asks where a feature sits relative to the
            // artwork, and dragging or spinning the design changes that answer as much as resizing
            // does — artists drag far more often than they pinch.
            // Forget the last publish whenever the design is not placed, so the first placed frame
            // afterwards always republishes rather than comparing against a footprint from before
            // the gap. Note this is a weak guard on its own: `anchorEstablished` is a one-way latch,
            // so `placed` does not drop on a re-capture — the anchor generation below is what
            // carries that case.
            if (!placed) designMoveDetector.reset()
            val designMoved = placed && designMoveDetector.moved(
                overlayPanX, overlayPanY, overlayRotationDeg, newHalfW, newHalfH,
                slamManager.anchorGeneration.value,
            )
            // Anchor-RELATIVE, not world — see FingerprintPartition.DesignFootprint. The live anchor
            // drifts and is corrected by reloc, so its world pose at two moments is not the same
            // physical place, and pairing this with the fingerprint's capture-time anchor pose is
            // what makes the two ends comparable at all.
            //
            // It cancels the anchor's TRANSLATION exactly: overlayBaseScratch takes its translation
            // from anchorMatrix, so that factor divides out. It does NOT cancel the rotation —
            // overlayBaseScratch overwrites the anchor's 3x3 with a world-fixed frame built from the
            // captured surface normal and world-up, so this product carries R_anchorᵀ and moves when
            // the anchor's orientation estimate does. That is a real residual and it is why the
            // repartition trigger below reads the artist's inputs instead of this matrix. For Φ
            // itself the residual is tolerable: a fraction of a degree moves a design edge by
            // millimetres. Note that is an argument about SMALL corrections, and PoseFusion also
            // performs cold snaps that are not small — a 3° relock at a one-metre lever arm moves an
            // edge ~5 cm, past DEFAULT_INNER_MARGIN. Nothing bounds that today; E8 is where the
            // margins and this interaction get measured rather than asserted.
            var footprint: com.hereliesaz.graffitixr.feature.ar.anchor.FingerprintPartition.DesignFootprint? = null
            synchronized(designFootprintLock) {
                val invertible =
                    android.opengl.Matrix.invertM(designAnchorInvScratch, 0, anchorMatrix, 0)
                if (placed && invertible) {
                    android.opengl.Matrix.multiplyMM(
                        designRigidModel, 0, designAnchorInvScratch, 0, overlayRigidScratch, 0,
                    )
                }
                designPlaced = placed && invertible
                if (designPlaced && designMoved) {
                    footprint = com.hereliesaz.graffitixr.feature.ar.anchor.FingerprintPartition
                        .DesignFootprint(designRigidModel.copyOf(), newHalfW, newHalfH)
                }
            }
            footprint?.let(onDesignFootprintChanged)
            android.opengl.Matrix.scaleM(overlayLocalScratch, 0, overlayScale, overlayScale, 1f)
            android.opengl.Matrix.multiplyMM(
                overlayComposedScratch, 0, overlayBaseScratch, 0, overlayLocalScratch, 0
            )

            // Build the 2D perspective content rotation matrix for X/Y, matching Compose's
            // graphicsLayer rotationX/Y. The quad stays flat on the wall; only the content
            // appearance tilts. Camera distance scales with half-extent for consistent feel.
            val contentRot = buildContentRotation(overlayRotationX, overlayRotationY)

            overlayRenderer.draw(viewMatrix, projMatrix, overlayComposedScratch,
                if (hasMeshData) meshVerticesBuffer else null,
                if (hasMeshData) meshWeightsBuffer else null,
                contentRot
            )

            val showBorder = !anchorEstablished && !isCapturingTarget && showAnchorBoundary
            if (showBorder) {
                overlayRenderer.drawAnchorBorder(viewMatrix, projMatrix, anchorMatrix)
            }

            // Diagnostic perception view ("what is the AR seeing"): drawn last so it sits on top
            // of the artwork overlay. Shows ALL active layers of perception, not just ARCore's:
            //  - current-frame ARCore feature points (yellow, via ArDebugRenderer)
            //  - tracked planes as metric grids (orientation-readable, not silhouettes)
            //  - whatever representation the SLAM engine is actually building right now:
            //    CLOUD_POINTS mode -> the accumulated point cloud; MURAL mode -> the native
            //    engine's own draw (voxel splats or surface mesh per muralMethod).
            lastStep = "debugView"
            // Throttled perception: refresh the world-locked layers (coverage, scan grids, feature
            // points, plane grids, point cloud, voxel splats, mesh) into the FBO only when the pose
            // moved or the map grew, capped at the selected rate (floored to 30 fps under thermal /
            // battery / lag), then composite every frame. Camera + overlay + gestures stay full-rate.
            // FBO-unavailable fallback: draw straight to the screen every frame so perception is never
            // blank. Relocating coverage + scan grids here moves them from under the artwork overlay to
            // over it, which is invisible during scanning (no overlay placed yet).
            if (perceptionFbo.ready && perceptionFbo.isSized()) {
                val nowMs = android.os.SystemClock.elapsedRealtime()
                val intervalMs = 1000f / effectivePerceptionFps()
                val due = nowMs - lastPerceptionRefreshMs >= intervalMs
                val moved = perceptionPoseChanged(viewMatrix)
                val splatCount = slamManager.getSplatCount()
                val mapGrew = splatCount != lastPerceptionSplatCount
                // A plane mid-dissolve changes every frame with nothing else moving, so it has to
                // count as a reason to redraw. Otherwise holding the phone still — exactly when the
                // artist is watching the surfaces settle — would freeze the dissolve part-way.
                val dissolving = nowMs < planeRenderer.dissolveCompletesAtMs
                val refresh = !havePerceptionCache || (due && (moved || mapGrew || dissolving))
                if (refresh) {
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    perceptionFbo.bindForRender()
                    drawPerceptionLayers(frame, activeSession, camera, viewMatrix, projMatrix, scanActive, voxelRevealMaskActive, isTracking)
                    perceptionFbo.unbind(surfaceWidth, surfaceHeight)
                    System.arraycopy(viewMatrix, 0, lastPerceptionView, 0, 16)
                    lastPerceptionRefreshMs = nowMs
                    lastPerceptionSplatCount = splatCount
                    havePerceptionCache = true
                    val dt = (android.os.SystemClock.elapsedRealtime() - t0).toFloat()
                    perceptionRefreshAvgMs = perceptionRefreshAvgMs * 0.9f + dt * 0.1f
                }
                // During voxel scanning, composite the perception layers as a dark reveal mask: the
                // mapped (voxel-covered) world shows through bright and full-colour, the rest dims.
                perceptionFbo.composite(reveal = voxelRevealMaskActive, dim = 0.85f)
            } else {
                drawPerceptionLayers(frame, activeSession, camera, viewMatrix, projMatrix, scanActive, voxelRevealMaskActive, isTracking)
            }
            // A stall reported at "frameDone" means onDrawFrame COMPLETED and the GL thread never
            // came back for the next frame — wedge is in eglSwapBuffers / the GLThread scheduler /
            // a pause request, not in this frame body.
            lastStep = "frameDone"
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
        var bestDot = 0f
        var bestArea = 0f

        for (plane in planes) {
            if (plane.trackingState != TrackingState.TRACKING) continue
            // A subsumed plane is a stale fragment ARCore has already merged into a larger one. It
            // keeps its own centerPose, so leaving it in the search let the anchor snap onto a
            // leftover shard of the wall and jump between shards as the artist moved.
            if (plane.subsumedBy != null) continue

            val pose = plane.centerPose
            val dx = pose.tx() - camX
            val dy = pose.ty() - camY
            val dz = pose.tz() - camZ
            val len = kotlin.math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
            if (len < 0.3f || len > 15f) continue

            val dot = (dx * fwdX + dy * fwdY + dz * fwdZ) / len
            if (dot < PLANE_PICK_MIN_DOT) continue
            val area = plane.extentX * plane.extentZ
            // Within a tie band of "most directly ahead", prefer the LARGER plane. ARCore leaves
            // co-planar fragments of one wall un-subsumed, and they all point roughly the same way,
            // so a raw argmax on the dot product picked whichever shard happened to be centred
            // nearest the view axis — the anchor then hopped between fragments of the same surface
            // from frame to frame, which reads as the overlay refusing to settle.
            val better = when {
                bestPlane == null -> true
                dot > bestDot + PLANE_PICK_DOT_TIE -> true   // clearly more centred
                dot < bestDot - PLANE_PICK_DOT_TIE -> false  // clearly less centred
                else -> area > bestArea                      // as centred as each other → bigger wins
            }
            if (better) {
                // max(), so the tie band stays anchored to the best centring seen rather than
                // sliding every time a larger-but-less-centred plane wins.
                bestDot = kotlin.math.max(bestDot, dot)
                bestArea = area
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
        arDebugRenderer.release()
        perceptionFbo.release()
    }

    /**
     * Detach the session with a bounded wait for the GL thread to leave the frame body.
     * Returns true when [sessionLock] was acquired within [timeoutMs] — i.e. the GL thread is
     * provably outside [onDrawFrame] and (with the session nulled) cannot touch ARCore again.
     * Returns false when the GL thread stayed wedged inside the frame (e.g. blocked in
     * session.update() on a camera that never feeds); the @Volatile session is still nulled so
     * no NEW frame starts, but the wedged frame holds its own stale reference — the caller must
     * rely on Session.close() to absorb the in-flight update(). Safe from any thread; never
     * blocks longer than [timeoutMs]. Does NOT set [isDestroying]: callers that are tearing down
     * set it themselves, while live-reconfigure callers re-attach via [attachSession] afterwards.
     */
    fun detachSessionBounded(timeoutMs: Long): Boolean {
        val locked = try {
            sessionLock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        try {
            session = null
        } finally {
            if (locked) sessionLock.unlock()
        }
        return locked
    }

    /**
     * Non-GL teardown: stops the render loop, cancels the background coroutine
     * scope (previously never cancelled — a coroutine leak), detaches the session,
     * and drops retained references. Safe to call from any thread — including the
     * main thread while the GL thread is wedged inside [onDrawFrame]: the session
     * detach uses a bounded tryLock instead of an unconditional withLock, because
     * the GL thread holds [sessionLock] for the whole frame and can block forever
     * inside session.update() / a native SLAM call when the camera never feeds.
     * An unconditional withLock here hard-froze the entire app on AR exit. GL
     * objects are freed separately via [releaseGlResources] on the GL thread.
     */
    fun destroy() {
        isDestroying = true
        watchdog?.interrupt()
        watchdog = null
        backgroundScope.cancel("Renderer detached and destroyed.")
        // Bounded acquisition only. If the GL thread is wedged mid-frame holding the lock,
        // fall through and null the @Volatile session anyway: isDestroying (checked at the
        // top of onDrawFrame) already stops any NEW frame from touching it, and the wedged
        // frame captured its own local reference — blocking the caller helps nothing.
        val locked = try {
            sessionLock.tryLock(500, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        try {
            session = null
        } finally {
            if (locked) sessionLock.unlock()
        }
        failPendingHitTests()
        anchorOrchestrator.clear()
        pendingOverlayBitmap = null
        latestFrame.set(null)
    }

    private companion object {
        // PnP inlier floor for the doodle swap — self-grow itself trusts a relock at >= 20 inliers
        // (MobileGS), so the same bar means "relocalization has confidently latched onto the marks".
        const val DOODLE_MIN_RELOC_INLIERS = 20

        // Multiplier for the overlay half-extent to derive the camera distance used in the
        // 2D perspective content rotation (matching Compose's graphicsLayer rotationX/Y).
        // Higher values → subtler perspective; lower → more dramatic. 3.0 gives a moderate
        // effect visible at 20-30° and dramatic at 45°+.
        const val CONTENT_PERSPECTIVE_FACTOR = 3.0f

        // Anchor plane pick: minimum cos(angle) between the camera forward axis and the direction to
        // a plane's centre for that plane to be considered "ahead" at all (the previous inline 0.4f).
        const val PLANE_PICK_MIN_DOT = 0.4f
        // Planes whose centring differs by less than this count as equally centred, and the larger
        // one wins — so the anchor settles on the whole wall instead of the nearest-centred fragment.
        const val PLANE_PICK_DOT_TIE = 0.05f

        const val PERCEPTION_FULL_FPS = 60
        const val PERCEPTION_FLOOR_FPS = 30
        // Rolling-average perception-refresh cost (ms) above which the lag trigger floors the rate.
        const val PERCEPTION_LAG_MS = 16f
        // Per-element view-matrix delta below which the pose is treated as unchanged (skip redraw).
        const val PERCEPTION_POSE_EPSILON = 1e-4f

        // --- Adaptive idle gating ---
        // Per-element view-matrix delta below which the phone is treated as "still" for idle. Coarser
        // than the perception epsilon so handheld micro-jitter doesn't count as motion (≈0.09° / 1.5mm).
        const val IDLE_POSE_EPSILON = 1.5e-3f
        // Continuous no-motion + no-interaction time before entering idle (slow to sleep).
        const val IDLE_ENTER_DEBOUNCE_MS = 700L
        // After resuming from idle, hold full rate this long so a single threshold-straddling jitter
        // frame can't immediately re-sleep (instant to wake, brief hold).
        const val RESUME_HOLD_MS = 500L
    }
}

/**
 * Angular velocity (rad/s) from two unit rotation quaternions [x,y,z,w] sampled [dt] apart:
 * the relative rotation q_rel = q_cur * conj(q_prev), converted to an axis-angle vector divided
 * by dt. Returns a zero vector for degenerate inputs.
 */
private fun angularVelocity(prev: FloatArray, cur: FloatArray, dt: Float): FloatArray {
    if (dt <= 0f) return floatArrayOf(0f, 0f, 0f)
    val px = -prev[0]; val py = -prev[1]; val pz = -prev[2]; val pw = prev[3] // conj(prev)
    val cx = cur[0]; val cy = cur[1]; val cz = cur[2]; val cw = cur[3]
    // q_rel = cur * conj(prev)  (Hamilton product)
    var rw = cw * pw - cx * px - cy * py - cz * pz
    var rx = cw * px + cx * pw + cy * pz - cz * py
    var ry = cw * py - cx * pz + cy * pw + cz * px
    var rz = cw * pz + cx * py - cy * px + cz * pw
    val n = kotlin.math.sqrt(rw * rw + rx * rx + ry * ry + rz * rz)
    if (n <= 1e-6f) return floatArrayOf(0f, 0f, 0f)
    rw /= n; rx /= n; ry /= n; rz /= n
    if (rw < 0f) { rw = -rw; rx = -rx; ry = -ry; rz = -rz } // shorter arc
    val angle = 2f * kotlin.math.acos(rw.coerceIn(-1f, 1f))
    val s = kotlin.math.sqrt(1f - rw * rw)
    if (s <= 1e-6f) return floatArrayOf(0f, 0f, 0f)
    val k = angle / (s * dt)
    return floatArrayOf(rx * k, ry * k, rz * k)
}