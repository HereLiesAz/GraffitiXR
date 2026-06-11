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
    @Volatile var showVoxels: Boolean = true          // SLAM voxel splats (confidence-tinted)
    @Volatile var showPoints: Boolean = true          // accumulated sparse point cloud
    @Volatile var showMesh: Boolean = true            // persistent surface mesh
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
    // Set by updateFlashlight (any thread); consumed at the top of onDrawFrame so the ARCore
    // configure() runs on the GL thread under sessionLock, never concurrently with update().
    @Volatile private var flashDirty: Boolean = false

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
                // Reset so the per-frame startup heartbeat fires on every (re)attach/resume, not just
                // the first session — resume is a common spot for camera/GL init stalls.
                frameCount = 0
                resetCameraStreamWatchdog()
                displayRotationHelper.onResume()
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
        onDiag("surface: onSurfaceCreated start")
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        backgroundRenderer.createOnGlThread(context)
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
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        // Mode-exit was requested: stop driving ARCore so teardown is effectively
        // instantaneous and we never touch a session that is being closed.
        if (isDestroying) return
        frameCount++
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
            val scanActive = !anchorEstablished && scanMode == ArScanMode.MURAL && scanPhase == ScanPhase.AMBIENT
            if (scanActive) backgroundRenderer.updateScanMask(visitedSectorsMask)
            lastStep = "bgDraw"
            backgroundRenderer.draw(frame, scanActive)
            if (frameCount <= 10 || frameCount % 60 == 0) {
                Timber.i("ARDIAG drawFrame f=$frameCount tracking=${frame.camera.trackingState} anchor=$anchorEstablished scanMode=$scanMode scanActive=$scanActive")
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
            if (scanActive && !hideVisualization) {
                val cam = frame.camera
                if (cam.trackingState == com.google.ar.core.TrackingState.TRACKING) {
                    val pv = FloatArray(16); cam.getProjectionMatrix(pv, 0, 0.1f, 100.0f)
                    val vv = FloatArray(16); cam.getViewMatrix(vv, 0)
                    planeRenderer.drawPlanes(activeSession, vv, pv, cam.pose, gridMode = true)
                }
            }

            lastStep = "anchorEstablish"
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

            lastStep = "slamCamera"
            slamManager.updateCamera(viewMatrix, projMatrix, mappingViewMatrix, mappingProjMatrix, frame.timestamp)

            lastStep = "light"
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

            val currentScanMode = scanMode
            slamManager.setArScanMode(currentScanMode.ordinal)
            slamManager.setMuralMethod(muralMethod.ordinal)
            
            // --- Democratic Consensus Transformation + smoothed reloc fusion ---
            // Backbone: ARCore consensus once anchored, else the native cached pose (as before).
            lastStep = "consensus"
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
            lastStep = "uiTick"
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

            lastStep = "capture"
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

                        var fx = intrinsics.focalLength[0]
                        var fy = intrinsics.focalLength[1]
                        var cx = intrinsics.principalPoint[0]
                        var cy = intrinsics.principalPoint[1]
                        val dims = intrinsics.imageDimensions
                        val rawW = dims[0].toFloat()
                        val rawH = dims[1].toFloat()

                        when (rotationNeeded) {
                            90 -> {
                                val t_fx = fx; fx = fy; fy = t_fx
                                val t_cx = cx; cx = rawH - cy; cy = t_cx
                            }
                            180 -> {
                                cx = rawW - cx
                                cy = rawH - cy
                            }
                            270 -> {
                                val t_fx = fx; fx = fy; fy = t_fx
                                val t_cx = cx; cx = cy; cy = rawW - t_cx
                            }
                        }

                        val intrArr = floatArrayOf(fx, fy, cx, cy)

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

            lastStep = "export"
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
            lastStep = "slamFeed"
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
                            if (currentScanMode == ArScanMode.CLOUD_POINTS || showPoints) {
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
            val hasMeshData = if (scanMode == ArScanMode.MURAL && muralMethod == MuralMethod.SURFACE_MESH) {
                slamManager.getPersistentMesh(meshVerticesBuffer, meshWeightsBuffer)
                true
            } else false

            lastStep = "overlayDraw"
            overlayRenderer.draw(viewMatrix, projMatrix, anchorMatrix, 
                if (hasMeshData) meshVerticesBuffer else null,
                if (hasMeshData) meshWeightsBuffer else null
            )

            val showBorder = !anchorEstablished && !isCapturingTarget &&
                (showAnchorBoundary || (showBorderForConfirmation && !overlayRenderer.hasTexture))
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
            val anyLayerOn = showFeaturePoints || showPlaneGrids || showVoxels || showPoints || showMesh
            if (anyLayerOn && isTracking && !isCapturingTarget) {
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
                if (showVoxels || showMesh) {
                    slamManager.drawDebugLayers(voxels = showVoxels, mesh = showMesh)
                }
                if (frameCount % 120 == 0) {
                    // Decides "no data" vs "drawn but invisible" from the diag log alone.
                    val planeCount = activeSession.getAllTrackables(com.google.ar.core.Plane::class.java)
                        .count { it.trackingState == com.google.ar.core.TrackingState.TRACKING && it.subsumedBy == null }
                    onDiag("debugView: feat=${arDebugRenderer.lastPointCount} planes=$planeCount voxels=${slamManager.getSplatCount()} pts=${pointCloudRenderer.accumulatedPointCount} method=$muralMethod")
                }
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
        arDebugRenderer.release()
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
}