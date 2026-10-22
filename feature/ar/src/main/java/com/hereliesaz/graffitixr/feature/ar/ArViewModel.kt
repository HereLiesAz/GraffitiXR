// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt
package com.hereliesaz.graffitixr.feature.ar

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.camera.core.ImageProxy
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.common.util.imageStats
import com.hereliesaz.graffitixr.feature.ar.anchor.DoodlePartition
import com.hereliesaz.graffitixr.feature.ar.anchor.MetricDoodleBuilder
import com.hereliesaz.sphereslam.math.RigidMath
import com.hereliesaz.graffitixr.feature.ar.anchor.StandaloneFingerprintBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.hereliesaz.graffitixr.common.model.CameraTargetFps
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.MeasureUi
import com.hereliesaz.graffitixr.common.model.TapMark
import com.hereliesaz.graffitixr.common.model.ScanPhase
import com.hereliesaz.graffitixr.common.sensor.Vec3
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import com.hereliesaz.graffitixr.common.util.isolateMarkings
import com.hereliesaz.graffitixr.common.util.MarkEraser
import com.hereliesaz.graffitixr.common.util.forDoodleReview
import com.hereliesaz.graffitixr.common.wearable.ConnectionState
import com.hereliesaz.graffitixr.common.wearable.WearableManager
import com.hereliesaz.graffitixr.feature.ar.coop.calibration.Mat4
import com.hereliesaz.graffitixr.feature.ar.coop.calibration.Procrustes
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.feature.ar.rendering.SessionLifecycleOutcome
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import com.hereliesaz.graffitixr.data.ProjectManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.design.R as DesignR
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import timber.log.Timber
import javax.inject.Inject

/**
 * Retries a [SessionLifecycleOutcome]-returning [request] (an [ArRenderer.requestResume] or
 * [ArRenderer.requestPause] call) up to [maxRetries] times when it reports
 * [SessionLifecycleOutcome.LockTimeout] — the GL thread was still inside `onDrawFrame` holding its
 * session lock when the bounded wait expired. That is usually a single slow frame finishing (a heavy
 * perception redraw, a GC pause), not a permanently wedged thread, so a few short retries give it a
 * chance to release the lock before the caller gives up. [sleeper] is injected (default
 * [Thread.sleep]) so a test can assert the retry count and give-up outcome without real wall-clock
 * waits or a live [ArRenderer].
 *
 * Deliberately top-level and free of any [ArViewModel]/[ArRenderer] instance state: this is the part
 * of the LockTimeout fix that actually needs a unit test, and `ArRenderer` cannot be mocked in this
 * module's JVM test harness (see `ArSessionTest`'s note) — extracting the retry/give-up decision here
 * makes it testable with a plain lambda standing in for the renderer call.
 */
internal fun retryOnLockTimeout(
    maxRetries: Int = ArViewModel.LOCK_TIMEOUT_MAX_RETRIES,
    delayMs: Long = ArViewModel.LOCK_TIMEOUT_RETRY_DELAY_MS,
    sleeper: (Long) -> Unit = { Thread.sleep(it) },
    request: () -> SessionLifecycleOutcome,
): SessionLifecycleOutcome {
    var outcome = request()
    var attempt = 0
    while (outcome is SessionLifecycleOutcome.LockTimeout && attempt < maxRetries) {
        attempt++
        sleeper(delayMs)
        outcome = request()
    }
    return outcome
}

internal fun shouldRestoreLocalArCoreDoodle(
    isArCoreAvailable: Boolean,
    coopRole: com.hereliesaz.graffitixr.common.model.CoopRole,
    peerSpatialFramePresent: Boolean,
): Boolean =
    isArCoreAvailable &&
        !(coopRole == com.hereliesaz.graffitixr.common.model.CoopRole.GUEST &&
            peerSpatialFramePresent)

/**
 * A standalone target-capture tap as `[nx, ny]` fractions of the full [frameWidth] × [frameHeight]
 * frame. [tap] is a fraction of the camera view of [viewSize] pixels, which shows the frame
 * `FILL_CENTER`; without a view size the tap is taken as already frame-relative, and without a tap
 * the frame centre is used (the centre is the centre under any centred crop).
 */
internal fun standaloneTapInFrame(
    tap: Pair<Float, Float>?,
    viewSize: Pair<Int, Int>?,
    frameWidth: Int,
    frameHeight: Int,
): FloatArray {
    if (tap == null) return floatArrayOf(0.5f, 0.5f)
    if (viewSize == null) return floatArrayOf(tap.first, tap.second)
    return com.hereliesaz.sphereslam.camera.CameraFillCenter.viewToFrameNormalized(
        nx = tap.first,
        ny = tap.second,
        frameWidth = frameWidth,
        frameHeight = frameHeight,
        viewWidth = viewSize.first,
        viewHeight = viewSize.second,
    ) ?: floatArrayOf(tap.first, tap.second)
}

data class StandaloneFingerprintPlacement(
    /** Column-major GL map_from_doodle transform. */
    val mapFromFingerprint: FloatArray,
    /** Width of the captured doodle plane in photosphere map units. */
    val referenceWidthUnits: Float,
    /** True only when the photosphere supplied a physical range rather than normalized radius. */
    val physicallyMetric: Boolean,
    val tileId: com.hereliesaz.sphereslam.TileId,
) {
    init {
        require(mapFromFingerprint.size == 16 && mapFromFingerprint.all { it.isFinite() })
        require(referenceWidthUnits.isFinite() && referenceWidthUnits > 0f)
    }
}

@HiltViewModel
class ArViewModel @Inject constructor(
    private val slamManager: SlamManager,
    private val projectRepository: ProjectRepository,
    private val settingsRepository: SettingsRepository,
    private val projectManager: com.hereliesaz.graffitixr.data.ProjectManager,
    private val collaborationManager: com.hereliesaz.graffitixr.core.collaboration.CollaborationManager,
    private val wearableManager: WearableManager,
    @param:ApplicationContext private val appContext: Context,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArUiState())
    val uiState: StateFlow<ArUiState> = _uiState.asStateFlow()

    // One-off, user-facing feedback (e.g. "camera unavailable") so AR failures surface
    // instead of leaving a silent black screen. Collected by the screen and shown as a toast.
    private val _feedback = MutableSharedFlow<com.hereliesaz.graffitixr.common.model.FeedbackEvent>(extraBufferCapacity = 4)
    val feedback: SharedFlow<com.hereliesaz.graffitixr.common.model.FeedbackEvent> = _feedback.asSharedFlow()

    private val _hostQrPayload = MutableStateFlow<String?>(null)
    val hostQrPayload: StateFlow<String?> = _hostQrPayload

    /**
     * Routes incoming spectator Ops to EditorViewModel without ArViewModel knowing about the
     * editor module (avoids the ViewModel-of-ViewModel anti-pattern). Set by MainActivity via
     * [setSpectatorOpHandler].
     *
     * Ops that arrive before the handler is wired (the host can start emitting deltas the moment
     * the guest connects, while the handler is assigned from a Compose LaunchedEffect) are held
     * in [pendingSpectatorOps] and replayed in order once the handler lands — they used to be
     * logged and permanently dropped, silently desyncing the guest canvas.
     */
    @Volatile private var spectatorOpHandler: ((com.hereliesaz.graffitixr.common.model.Op) -> Unit)? = null

    // Guarded by synchronized(pendingSpectatorOps). Bounded so early LayerBitmapReplace payloads
    // can't pin unbounded memory if the handler never arrives.
    private val pendingSpectatorOps = ArrayDeque<com.hereliesaz.graffitixr.common.model.Op>()
    private val MAX_PENDING_SPECTATOR_OPS = 128

    fun setSpectatorOpHandler(handler: (com.hereliesaz.graffitixr.common.model.Op) -> Unit) {
        // Drain the backlog fully *before* publishing the handler. If we published first and then
        // drained, an op arriving on the network thread mid-drain would see a non-null handler and
        // run ahead of the still-queued backlog ops, desyncing the canvas. Publishing only once the
        // queue is empty keeps strict FIFO: anything arriving during the drain is appended (handler
        // still null) and picked up by the loop.
        while (true) {
            val op = synchronized(pendingSpectatorOps) {
                if (pendingSpectatorOps.isEmpty()) {
                    spectatorOpHandler = handler
                    return
                }
                pendingSpectatorOps.removeFirst()
            }
            handler(op)
        }
    }

    // ── Host side of co-op v4: edits a connected guest made ─────────────────────────────────
    // Collected HERE, for the ViewModel's lifetime, rather than in an Activity-scoped effect: the
    // host session acknowledges a guest edit on arrival, so one that reached a collector that was
    // being torn down (Activity recreation) would be acked and never applied. Unbounded on purpose
    // for the same reason. Nothing here is large: GuestOpPolicy refuses the only bitmap-carrying
    // guest ops (DesignReplace, DesignBitmapReplace) in HostSession before they reach this queue.
    private val pendingGuestOps = ArrayDeque<com.hereliesaz.graffitixr.common.model.Op>()
    @Volatile private var guestOpHandler: ((com.hereliesaz.graffitixr.common.model.Op) -> Unit)? = null

    init {
        viewModelScope.launch {
            collaborationManager.guestOps.collect { op ->
                val handler = synchronized(pendingGuestOps) {
                    guestOpHandler ?: run { pendingGuestOps.addLast(op); null }
                }
                handler?.invoke(op)
            }
        }
        // Tripod stream (Link): read the composited view back only while a display guest wants it.
        viewModelScope.launch {
            collaborationManager.videoWanted.collect { applyTripodSink(it) }
        }
        // Display device: decode each streamed JPEG off the main thread into the state the view draws.
        viewModelScope.launch(Dispatchers.Default) {
            collaborationManager.video.collect { jpeg ->
                val frame = jpeg?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                _uiState.update { if (it.tripodView) it.copy(tripodFrame = frame) else it }
            }
        }
    }

    /**
     * Guest: switch between this device's own AR view and the tripod's streamed view. Asking the host
     * for frames only while the tripod view is up keeps the host from encoding for nobody.
     */
    fun toggleTripodView() {
        if (_uiState.value.coopRole != com.hereliesaz.graffitixr.common.model.CoopRole.GUEST) return
        val show = !_uiState.value.tripodView
        collaborationManager.setVideoWanted(show)
        _uiState.update { it.copy(tripodView = show, tripodFrame = null) }
    }

    private val tripodEncoding = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun applyTripodSink(wanted: Boolean) {
        renderer?.streamSink = if (wanted) { bmp -> encodeTripodFrame(bmp) } else null
    }

    /**
     * Runs on the GL thread, so it only hands the bitmap off: downscale and JPEG on a worker, one
     * frame in flight (a frame arriving while the last is still encoding is dropped, latest wins).
     */
    private fun encodeTripodFrame(bmp: Bitmap) {
        if (!tripodEncoding.compareAndSet(false, true)) { bmp.recycle(); return }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val edge = maxOf(bmp.width, bmp.height)
                val scaled = if (edge > TRIPOD_MAX_EDGE_PX) {
                    val k = TRIPOD_MAX_EDGE_PX.toFloat() / edge
                    Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
                } else bmp
                val out = java.io.ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, TRIPOD_JPEG_QUALITY, out)
                if (scaled !== bmp) scaled.recycle()
                collaborationManager.offerVideo(out.toByteArray())
            } finally {
                bmp.recycle()
                tripodEncoding.set(false)
            }
        }
    }

    /**
     * Wire the editor that applies guest edits on the host (EditorViewModel.applyGuestOp). Drains
     * any backlog first, in order, before publishing — the same FIFO discipline as
     * [setSpectatorOpHandler].
     */
    fun setGuestOpHandler(handler: (com.hereliesaz.graffitixr.common.model.Op) -> Unit) {
        while (true) {
            val op = synchronized(pendingGuestOps) {
                if (pendingGuestOps.isEmpty()) {
                    guestOpHandler = handler
                    return
                }
                pendingGuestOps.removeFirst()
            }
            handler(op)
        }
    }

    /** Invokes the handler, or buffers the op (drop-oldest past the cap) until one is wired. */
    private fun dispatchSpectatorOp(op: com.hereliesaz.graffitixr.common.model.Op) {
        val handler = synchronized(pendingSpectatorOps) {
            spectatorOpHandler ?: run {
                if (pendingSpectatorOps.size >= MAX_PENDING_SPECTATOR_OPS) {
                    pendingSpectatorOps.removeFirst()
                    android.util.Log.w("ArViewModel", "Pending spectator op buffer full — dropping oldest")
                }
                pendingSpectatorOps.addLast(op)
                null
            }
        }
        handler?.invoke(op)
    }

    // ── Glasses session state ─────────────────────────────────────────────────
    private val _glassesSessionState: MutableStateFlow<GlassesSessionState> =
        MutableStateFlow(GlassesSessionState.Idle)
    val glassesSessionState: StateFlow<GlassesSessionState> = _glassesSessionState.asStateFlow()

    @Volatile var phoneToGlassesXform: Mat4? = null
        private set

    // Guards the calibration point lists, mutated from the connection-state collector,
    // the tap-submit coroutine, and the main thread (recalibrate / endGlassesSession).
    private val calibrationLock = Any()
    private val calibrationSrcPoints = mutableListOf<Vec3>()
    private val calibrationDstPoints = mutableListOf<Vec3>()
    // ─────────────────────────────────────────────────────────────────────────

    private var session: Session? = null
    private var renderer: ArRenderer? = null

    // Single tracked collector of collaborationManager.state. startHosting/joinFromQr previously
    // each launched their own forever-collect, so host→leave→host stacked collectors; this lets
    // each entry cancel the prior one and leaveSession stop it.
    private var coopStateJob: kotlinx.coroutines.Job? = null
    /** Host wall-frame identity frozen when Host was started; null outside a host session. */
    @Volatile private var hostedCoopSpatialFrame:
        com.hereliesaz.graffitixr.common.model.CoopSpatialFrame? = null

    private fun observeCoopState() {
        coopStateJob?.cancel()
        coopStateJob = viewModelScope.launch {
            collaborationManager.state.collect { newState ->
                _uiState.update { it.copy(coopSessionState = newState) }
            }
        }
    }

    /** Collector for [CollaborationManager.guestEditDropped]; cancelled alongside [coopStateJob]. */
    private var guestEditDropJob: kotlinx.coroutines.Job? = null

    /**
     * Tell a guest, once per session, that an edit they just made is theirs alone.
     *
     * Since co-op protocol v4 a guest's edits ARE sent to the host. This now fires only for the
     * ones that cannot be: an AR placement between devices on different tracking backends (each
     * expresses it in its own wall frame) or an edit too large for one frame. Silence there would
     * leave the two canvases diverging with neither side told. Once per session, because the point
     * is to explain the limit, not to nag every gesture.
     */
    private fun observeDroppedGuestEdits() {
        guestEditDropJob?.cancel()
        guestEditDropJob = viewModelScope.launch {
            collaborationManager.guestEditDropped.collect {
                _uiState.update { it.copy(guestEditWasDropped = true) }
                if (reportedGuestEditDrop) return@collect
                reportedGuestEditDrop = true
                _feedback.tryEmit(
                    com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(
                        appContext.getString(DesignR.string.ar_feedback_guest_edit_dropped)
                    )
                )
            }
        }
    }

    @Volatile private var reportedGuestEditDrop = false

    fun startHosting() {
        // serializeCurrentProject() zips the whole project directory and can run to several MB.
        viewModelScope.launch(dispatchers.io) {
            try {
                val st = _uiState.value
                if (!st.isArCoreAvailabilityResolved) {
                    _feedback.tryEmit(
                        com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(
                            appContext.getString(DesignR.string.ar_feedback_backend_resolving)
                        )
                    )
                    return@launch
                }
                val project = projectRepository.currentProject.value
                if (project == null) {
                    _feedback.tryEmit(
                        com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(
                            appContext.getString(DesignR.string.ar_feedback_open_project_first)
                        )
                    )
                    return@launch
                }

                val baseSpatial = CoopSpatialFrameFactory.fromProject(project, st.isArCoreAvailable)
                if (baseSpatial == null) {
                    _feedback.tryEmit(
                        com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(
                            if (st.isArCoreAvailable) {
                                appContext.getString(DesignR.string.ar_feedback_host_need_doodle_arcore)
                            } else {
                                appContext.getString(DesignR.string.ar_feedback_host_need_target_standalone)
                            }
                        )
                    )
                    return@launch
                }

                val initialDoodle = slamManager.exportDoodle() ?: ByteArray(0)
                val hostedSpatial = baseSpatial.copy(
                    doodleAvailable = initialDoodle.isNotEmpty(),
                )
                if (
                    hostedSpatial.hostBackend ==
                        com.hereliesaz.graffitixr.common.model.CoopTrackingBackend.ARCORE &&
                    !hostedSpatial.doodleAvailable
                ) {
                    _feedback.tryEmit(
                        com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(
                            appContext.getString(DesignR.string.ar_feedback_doodle_not_ready)
                        )
                    )
                    return@launch
                }

                if (projectManager.serializeCurrentProject().isEmpty()) {
                    _feedback.tryEmit(
                        com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(
                            appContext.getString(DesignR.string.ar_feedback_package_failed)
                        )
                    )
                    return@launch
                }

                val arCoreHost = st.isArCoreAvailable
                val qrString = collaborationManager.startHosting(
                    projectId = projectManager.currentProjectId(),
                    localDeviceName = android.os.Build.MODEL,
                ) {
                    val current = projectRepository.currentProject.value
                        ?: error("project closed while hosting")
                    val wireDoodle = slamManager.exportDoodle() ?: ByteArray(0)
                    val currentSpatial = CoopSpatialFrameFactory.fromProject(current, arCoreHost)
                        ?: error("host wall frame disappeared while hosting")
                    com.hereliesaz.graffitixr.core.collaboration.ProjectSnapshot(
                        doodleBytes = wireDoodle,
                        projectBytes = kotlinx.coroutines.runBlocking {
                            projectManager.serializeCurrentProject()
                        },
                        spatialFrame = currentSpatial.copy(
                            doodleAvailable = wireDoodle.isNotEmpty(),
                        ),
                    )
                }
                hostedCoopSpatialFrame = hostedSpatial
                arCorePeerDoodle = null
                _uiState.update {
                    it.copy(
                        coopRole = com.hereliesaz.graffitixr.common.model.CoopRole.HOST,
                        coopSessionState =
                            com.hereliesaz.graffitixr.common.model.CoopSessionState.WaitingForGuest,
                        coopPeerSpatialFrame = null,
                        coopPeerDoodle = null,
                    )
                }
                _hostQrPayload.value = qrString
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                hostedCoopSpatialFrame = null
                _uiState.update {
                    it.copy(
                        coopSessionState =
                            com.hereliesaz.graffitixr.common.model.CoopSessionState.Ended(
                                com.hereliesaz.graffitixr.common.model.CoopSessionState.EndReason.NetworkLost
                            )
                    )
                }
                android.util.Log.e("ArViewModel", "Failed to start hosting", e)
                val tooLarge = generateSequence(e as Throwable) { it.cause }
                    .filterIsInstance<com.hereliesaz.graffitixr.core.collaboration.session.ProjectTooLargeToHostException>()
                    .firstOrNull()
                val message = if (tooLarge != null) {
                    // Link holds one transfer in memory; export streams and takes twice that. Name
                    // the limit, and the way out.
                    appContext.getString(
                        DesignR.string.ar_feedback_host_too_large,
                        (tooLarge.bytes + MIB - 1) / MIB,
                        tooLarge.capBytes / MIB,
                    )
                } else {
                    appContext.getString(DesignR.string.ar_feedback_host_failed, e.message ?: appContext.getString(DesignR.string.ar_feedback_unknown_error))
                }
                _feedback.tryEmit(com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(message, e))
                return@launch
            }
            observeCoopState()
        }
    }

    /** The ARCore host's doodle this guest joined with, re-installed on every ARCore entry. */
    @Volatile private var arCorePeerDoodle: ByteArray? = null

    /**
     * Install the ARCore host's geometry: its doodle (PnP object frame -> host wall frame is explicit;
     * PoseFusion and return-visit anchoring consume this exact bridge), its paint marks (same doodle
     * frame; alignToPeer clears them, and a guest at a painted-over wall would otherwise have only the
     * original marks; the host's grown rows ride in the doodle blob), and its wall normal (earth frame,
     * so it holds on any device; without one, alignToPeer has already dropped this device's last).
     */
    private fun installArCorePeer(
        doodle: ByteArray,
        spatialFrame: com.hereliesaz.graffitixr.common.model.CoopSpatialFrame,
    ) {
        slamManager.alignToPeer(doodle)
        val project = projectRepository.currentProject.value
        project?.paintMarks?.takeIf { it.pointCount > 0 }?.let { slamManager.restorePaintMarks(it) }
        slamManager.setWallNormalEnu(project?.doodleWallNormalEnu?.takeIf { it.size == 3 }?.toFloatArray())
        slamManager.captureAnchorCam = spatialFrame.doodleFromWall.toFloatArray()
    }

    fun joinFromQr(qr: String) {
        viewModelScope.launch {
            try {
                val localBackend = CoopSpatialFrameFactory.localBackend(_uiState.value.isArCoreAvailable)
                collaborationManager.joinFromQr(
                    qr = qr,
                    localDeviceName = android.os.Build.MODEL,
                    localBackend = localBackend,
                    onBulkReceived = { doodle, project, spatialFrame ->
                        // Claim native tracking ownership BEFORE loadAsSpectator publishes the host
                        // project. That publication wakes the generic project collector; if it sees
                        // an ARCore guest without this marker it can asynchronously restore an older
                        // project.doodle over the just-received peer geometry.
                        _uiState.update {
                            it.copy(
                                coopRole = com.hereliesaz.graffitixr.common.model.CoopRole.GUEST,
                                coopPeerSpatialFrame = spatialFrame,
                                coopPeerDoodle =
                                    if (localBackend ==
                                        com.hereliesaz.graffitixr.common.model.CoopTrackingBackend.SPHERESLAM
                                    ) doodle.copyOf() else null,
                            )
                        }

                        // Project assets next: a standalone host's canonical KPM page/atlas arrives
                        // inside this archive and must exist before the CameraX analyzer can restore it.
                        if (
                            !projectManager.loadAsSpectator(project) { spectator ->
                                normalizeCoopSpectatorProject(
                                    project = spectator,
                                    spatialFrame = spatialFrame,
                                    guestBackend = localBackend,
                                    imageDimensions = { uri ->
                                        val opts = android.graphics.BitmapFactory.Options().apply {
                                            inJustDecodeBounds = true
                                        }
                                        runCatching {
                                            appContext.contentResolver.openInputStream(uri)?.use { input ->
                                                android.graphics.BitmapFactory.decodeStream(
                                                    input,
                                                    null,
                                                    opts,
                                                )
                                            }
                                        }
                                        if (opts.outWidth > 0 && opts.outHeight > 0) {
                                            opts.outWidth to opts.outHeight
                                        } else {
                                            null
                                        }
                                    },
                                )
                            }
                        ) {
                            _feedback.tryEmit(
                                com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(
                                    appContext.getString(DesignR.string.ar_feedback_join_load_failed), null
                                )
                            )
                            leaveSession()
                            return@joinFromQr
                        }

                        if (localBackend ==
                            com.hereliesaz.graffitixr.common.model.CoopTrackingBackend.ARCORE) {
                            if (doodle.isEmpty()) {
                                _feedback.tryEmit(
                                    com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(
                                        appContext.getString(DesignR.string.ar_feedback_join_no_geometry), null
                                    )
                                )
                                leaveSession()
                                return@joinFromQr
                            }
                            arCorePeerDoodle = doodle.copyOf()
                            installArCorePeer(doodle, spatialFrame)
                            // coopPeerSpatialFrame/role were published before spectator load so the
                            // generic project loader could not race this peer installation.
                        } else {
                            // Standalone consumes the shared KPM page when the host is SphereSLAM.
                            // For an ARCore host it instead feeds the peer doodle already stored
                            // above through MobileGS and composes PnP through doodleFromWall.
                        }
                    },
                    onOp = { op -> dispatchSpectatorOp(op) },
                )
                hostedCoopSpatialFrame = null
                _uiState.update {
                    it.copy(coopRole = com.hereliesaz.graffitixr.common.model.CoopRole.GUEST)
                }
                observeCoopState()
                reportedGuestEditDrop = false
                observeDroppedGuestEdits()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val managerTerminal =
                    collaborationManager.state.value as?
                        com.hereliesaz.graffitixr.common.model.CoopSessionState.Ended
                arCorePeerDoodle = null
                _uiState.update {
                    it.copy(
                        coopRole = com.hereliesaz.graffitixr.common.model.CoopRole.NONE,
                        coopPeerSpatialFrame = null,
                        coopPeerDoodle = null,
                        // Preserve a deliberate VersionMismatch/SpatialIncompatible decision from
                        // the transport instead of flattening every failed join into NetworkLost.
                        coopSessionState = managerTerminal
                            ?: com.hereliesaz.graffitixr.common.model.CoopSessionState.Ended(
                                com.hereliesaz.graffitixr.common.model.CoopSessionState.EndReason.NetworkLost
                            )
                    )
                }
                android.util.Log.e("ArViewModel", "Failed to join session", e)
                _feedback.tryEmit(
                    com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(
                        appContext.getString(DesignR.string.ar_feedback_join_failed, e.message ?: appContext.getString(DesignR.string.ar_feedback_unknown_error)), e
                    )
                )
            }
        }
    }

    fun leaveSession() {
        viewModelScope.launch {
            coopStateJob?.cancel()
            coopStateJob = null
            guestEditDropJob?.cancel()
            guestEditDropJob = null
            collaborationManager.leaveSession()
            reportedGuestEditDrop = false
            hostedCoopSpatialFrame = null
            arCorePeerDoodle = null
            _uiState.update {
                it.copy(
                    coopRole = com.hereliesaz.graffitixr.common.model.CoopRole.NONE,
                    coopSessionState = com.hereliesaz.graffitixr.common.model.CoopSessionState.Idle,
                    coopPeerSpatialFrame = null,
                    coopPeerDoodle = null,
                    guestEditWasDropped = false,
                    tripodView = false,
                    tripodFrame = null,
                )
            }
            _hostQrPayload.value = null
        }
    }

    fun dismissCoopNotFoundDialog() {
        _uiState.update { it.copy(showCoopNotFoundDialog = false) }
    }

    // Eval (Sub-project A) — dev-only.
    private val evalProbe by lazy {
        com.hereliesaz.graffitixr.feature.ar.eval.DriftCostProbe(
            context = appContext,
            deviceClass = if (_uiState.value.isHardwareStereoActive) "dual" else "mono",
            nowMs = { android.os.SystemClock.elapsedRealtime() },
        )
    }
    private val evalRecorder by lazy {
        com.hereliesaz.graffitixr.feature.ar.eval.ArRecordingController(appContext)
    }

    // True only while a dev eval session is logging. Gates the live-metrics publish so the lazy
    // evalProbe (and its BatteryManager) is never instantiated in release / normal use.
    @Volatile private var evalLogging = false

    // ── Self-collecting diagnostics ───────────────────────────────────────────
    //
    // Always on, unlike everything above it. The eval probe has to be armed before a run, which is
    // fine for a planned experiment and useless for the case that actually happens: something looks
    // wrong, and the evidence needed to say why has already scrolled past. This records the same
    // channels the HUD displays, continuously, so a report can be produced AFTER the fact.
    //
    // The cost is a bounded ring of objects the tick already allocates — see DiagnosticRecorder's
    // capacity — so there is nothing to gate it on.
    private val diagnosticRecorder = com.hereliesaz.graffitixr.feature.ar.eval.DiagnosticRecorder()
    private val diagnosticWatcher = com.hereliesaz.graffitixr.feature.ar.eval.DiagnosticWatcher()

    /**
     * Screenshots are the one part that IS gated, on the Diagnostic Overlay setting.
     *
     * Recording numbers into memory is invisible; writing PNGs of the user's camera feed to disk is
     * not, and must never happen because the app felt like it.
     *
     * The gate is the Diagnostic Overlay setting, which defaults off, is not persisted, and cannot
     * be reached without a deliberate flip. The setting's own label had to be changed to say what
     * the flip now authorises — it read "Depth Diagnostic Overlay", which promises a text HUD and
     * nothing about photographs — because a technically effective gate on a switch whose label
     * conceals what it does is not consent, it is a defensible-looking accident.
     */
    @Volatile private var diagnosticCaptureEnabled = false

    // This class used to keep its OWN `lastCaptureRefusal` field. It was wrong the moment a second
    // capture flow existed: `MainViewModel.handleSingleCapture` — the "tap directly on your painted
    // marks" review flow, which is the ORDINARY way a target gets created — builds a doodle
    // through the exact same `MetricDoodleBuilder`, on a failure it never touched this field,
    // and its refusal reached the artist only as an ephemeral `Toast`. A report built from this
    // class's own field then said "no refusal recorded" for a capture that HAD failed, moments
    // earlier, through the flow the artist actually used — which is indistinguishable, to the field,
    // from nothing having been attempted at all.
    //
    // `MetricDoodleBuilder.lastRefusalReason` is the shared answer both flows read and write
    // now; see its doc. The report below reads that directly.

    private val _captureRequests =
        MutableSharedFlow<com.hereliesaz.graffitixr.feature.ar.eval.CaptureTrigger>(
            extraBufferCapacity = 2,
        )

    /**
     * Asks the host Activity to photograph the window. It lives there and not here because the
     * camera preview and the AR overlay are a `SurfaceView`/GL surface: the view hierarchy's drawing
     * cache renders them as a black hole, and only `PixelCopy` against the window reads back what is
     * actually on screen.
     */
    val captureRequests:
        SharedFlow<com.hereliesaz.graffitixr.feature.ar.eval.CaptureTrigger> =
        _captureRequests.asSharedFlow()

    /** `file name → why it was taken`, in capture order, for the report's Screenshots section. */
    private val captureManifest = java.util.Collections.synchronizedList(ArrayList<String>())

    private val diagnosticSessionStartMs = System.currentTimeMillis()

    private val _diagnosticCaptureCount = MutableStateFlow(0)

    /**
     * Screenshots written this session, for the report button's label.
     *
     * Held here and not in the Activity because the Activity is destroyed and rebuilt on every
     * rotation while this is not. A counter on the Activity would reset to zero mid-session and the
     * button would claim no screenshots existed while the report listed six.
     */
    val diagnosticCaptureCount: StateFlow<Int> = _diagnosticCaptureCount.asStateFlow()

    private val captureIndex = AtomicInteger(0)
    private val capturesPurged = AtomicBoolean(false)

    fun setDiagnosticCaptureEnabled(enabled: Boolean) {
        val wasEnabled = diagnosticCaptureEnabled
        diagnosticCaptureEnabled = enabled
        if (enabled && !wasEnabled) {
            // The backend may have settled before diagnostics were enabled. Snapshot it now so the
            // log always answers which SphereSLAM mode is selected even if no transition happens
            // after the user flips the setting.
            appendDiag(_uiState.value.sphereSlamRuntimeStatus.diagnosticLine())
        }
    }

    private fun publishSphereSlamRuntimeStatus(
        status: com.hereliesaz.graffitixr.common.model.SphereSlamRuntimeStatus,
    ) {
        val previous = _uiState.value.sphereSlamRuntimeStatus
        if (previous == status) return
        _uiState.update { state -> state.copy(sphereSlamRuntimeStatus = status) }
        if (diagnosticCaptureEnabled) appendDiag(status.diagnosticLine())
    }

    private fun refreshSphereSlamRuntimeSelection() {
        val state = _uiState.value
        val mode = when {
            !state.isSphereSlamAvailabilityResolved || !state.isArCoreAvailabilityResolved ->
                com.hereliesaz.graffitixr.common.model.SphereSlamRuntimeMode.UNRESOLVED
            !state.isSphereSlamAvailable ->
                com.hereliesaz.graffitixr.common.model.SphereSlamRuntimeMode.UNAVAILABLE
            state.isArCoreAvailable ->
                com.hereliesaz.graffitixr.common.model.SphereSlamRuntimeMode.ARCORE_SIDECAR
            else ->
                com.hereliesaz.graffitixr.common.model.SphereSlamRuntimeMode.STANDALONE
        }
        val current = state.sphereSlamRuntimeStatus
        val sameMode = current.mode == mode
        publishSphereSlamRuntimeStatus(
            current.copy(
                available = state.isSphereSlamAvailable,
                mode = mode,
                active = if (sameMode) current.active else false,
                referenceReady = if (sameMode) current.referenceReady else false,
                trackingData = if (sameMode) current.trackingData else false,
            )
        )
    }

    /**
     * Reserves the next capture filename. Zero-padded index first, so the files sort in the order
     * the events happened — which is the order the report lists them in.
     *
     * The counter lives here for the same reason the count above does: a `var index` in the Compose
     * collector restarts at 1 after a rotation, and the second `01_LOCK_LOST.png` of a session
     * overwrites the first while the report goes on citing both.
     */
    fun nextDiagnosticCaptureFile(
        trigger: com.hereliesaz.graffitixr.feature.ar.eval.CaptureTrigger,
    ): File {
        val n = captureIndex.incrementAndGet()
        return File(
            diagnosticCapturesDir(),
            String.format(java.util.Locale.US, "%02d_%s.png", n, trigger.name),
        )
    }

    /** Called back by the Activity once a PNG has actually landed on disk. */
    fun onDiagnosticCaptureWritten(
        trigger: com.hereliesaz.graffitixr.feature.ar.eval.CaptureTrigger,
        fileName: String,
    ) {
        val atSec = (System.currentTimeMillis() - diagnosticSessionStartMs) / 1000
        captureManifest.add("`$fileName` — ${trigger.name} at +${atSec}s — ${trigger.whatToLookFor}")
        // Successes only. The manifest also carries failure lines, and a button counting those would
        // promise attachments that are not there.
        _diagnosticCaptureCount.update { it + 1 }
    }

    /**
     * Called back when a requested capture failed.
     *
     * Recorded rather than swallowed: the report's Screenshots section saying "none captured" has to
     * mean "no transition occurred", and a silently dropped `PixelCopy` would make it mean "or the
     * copy failed" — reintroducing exactly the ambiguity the section exists to remove. It also spent
     * a slot of the watcher's budget, so the reader is entitled to know.
     */
    fun onDiagnosticCaptureFailed(
        trigger: com.hereliesaz.graffitixr.feature.ar.eval.CaptureTrigger,
        reason: String,
    ) {
        val atSec = (System.currentTimeMillis() - diagnosticSessionStartMs) / 1000
        captureManifest.add("(failed) ${trigger.name} at +${atSec}s — $reason")
    }

    fun evalStartLog() {
        // Every run gets its identity sidecar. Making `identity` optional on start() shipped a
        // serializer with no caller — a run-identity feature that produced zero run identities,
        // ticked as done. EVALUATION.md 3.2's rule is that a CSV without a sidecar is not evidence,
        // so the call site has to supply one or the whole thing is decoration.
        // 6a.4 — seed RANSAC before the run so a replay A/B is a controlled comparison. Applied
        // BEFORE start() so the identity written to the sidecar and the seed actually in force are
        // the same value; setting it afterwards would leave a window where rows were produced under
        // a different RNG than the sidecar claims.
        //
        // Debug-gated at the call site rather than inside the engine: a fixed seed shipped to users
        // would make every device draw the identical RANSAC sample sequence forever, which is a
        // behaviour change and not an evaluation affordance.
        slamManager.setEvalRngSeedIfDebuggable(EVAL_RNG_SEED, BuildConfig.DEBUG)
        evalProbe.start(buildEvalRunIdentity())
        renderer?.driftCostProbe = evalProbe
        evalLogging = true
    }

    /**
     * Snapshot of everything needed to reconstruct this run: build, device, and the tunables whose
     * values the numbers depend on.
     *
     * `rngSeed` reports the seed that is actually in force, not a hopeful constant: it is null in a
     * release build, because [SlamManager.setEvalRngSeedIfDebuggable] declines to seed there, and a
     * sidecar claiming a seed the engine never applied would be worse than one admitting it has
     * none. `solvePnPRansac` draws random samples, so an unseeded replay A/B is not a controlled
     * comparison and the reader can only know that if the field says so.
     *
     * `syncReloc` is READ BACK from the engine, not remembered from whatever this class asked for.
     * In a release build `setEvalSyncRelocIfDebuggable` declines and the engine reports 0, so the
     * sidecar says async — which is the truth. A sidecar claiming a synchronous run that never
     * happened is worse evidence than no sidecar, because it is the field a reader uses to decide
     * whether two runs are comparable at all.
     */
    private fun buildEvalRunIdentity() = com.hereliesaz.graffitixr.feature.ar.eval.EvalRunIdentity(
        gitCommit = BuildConfig.GIT_COMMIT,
        recordingName = evalRecorder.currentPlaybackName,
        recordingSha256 = null, // hashing a multi-hundred-MB MP4 on the UI path is not worth it
        deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
        androidRelease = android.os.Build.VERSION.RELEASE ?: "unknown",
        deviceClass = if (_uiState.value.isHardwareStereoActive) "dual" else "mono",
        rngSeed = if (BuildConfig.DEBUG) EVAL_RNG_SEED else null,
        syncReloc = slamManager.evalSyncRelocEveryN() > 0,
        parameters = mapOf(
            "MIN_INLIER_RATIO" to
                com.hereliesaz.graffitixr.feature.ar.anchor.PoseFusion.MIN_INLIER_RATIO.toString(),
            "BASE_ALPHA" to
                com.hereliesaz.graffitixr.feature.ar.anchor.PoseFusion.BASE_ALPHA.toString(),
            "CONF_FLOOR" to
                com.hereliesaz.graffitixr.feature.ar.anchor.PoseFusion.CONF_FLOOR.toString(),
            "COLD_SNAP_INLIER_RATIO" to
                com.hereliesaz.graffitixr.feature.ar.anchor.PoseFusion.COLD_SNAP_INLIER_RATIO.toString(),
            "COLD_SNAP_MIN_INLIERS" to
                com.hereliesaz.graffitixr.feature.ar.anchor.PoseFusion.COLD_SNAP_MIN_INLIERS.toString(),
            "COLD_SNAP_DIST_M" to
                com.hereliesaz.graffitixr.feature.ar.anchor.PoseFusion.COLD_SNAP_DIST_M.toString(),
            "COLD_SNAP_ANGLE_DEG" to
                com.hereliesaz.graffitixr.feature.ar.anchor.PoseFusion.COLD_SNAP_ANGLE_DEG.toString(),
        ),
    )

    fun evalStopLog() {
        evalLogging = false
        renderer?.driftCostProbe = null
        evalProbe.stop()
    }

    /** Simulate a tracking loss: mark it for recovery timing and briefly pause mapping. */
    fun evalInduceLoss() {
        evalProbe.markTrackingLoss()
        slamManager.setMappingPaused(true)
        viewModelScope.launch { kotlinx.coroutines.delay(500); slamManager.setMappingPaused(false) }
    }

    /**
     * Debug-only (gated behind [EVAL_OVERLAY_ENABLED] at the call site), but the same footgun as
     * [resumeArSessionInternal]: `session.recordingStatus`/`startRecording` previously ran directly on
     * the caller's thread (a Compose onClick, so the main thread) with no guard against the GL
     * thread's concurrent `session.update()`. Routed through [ArRenderer.withLockedSession] so it is
     * serialized against [ArRenderer.onDrawFrame] the same way. When no renderer is attached (AR not
     * actually active — the eval overlay is only shown in AR mode, but a stale click during teardown
     * is possible), there is no GL thread that could be racing, so calling the session directly is
     * safe — matching the previous behaviour for that case.
     */
    fun evalStartRecording() {
        val s = session ?: return
        val r = renderer
        if (r != null) {
            r.withLockedSession { locked ->
                evalRecorder.startRecording(locked, "eval_${android.os.SystemClock.elapsedRealtime()}")
            }
        } else {
            evalRecorder.startRecording(s, "eval_${android.os.SystemClock.elapsedRealtime()}")
        }
    }

    fun evalStopRecording() {
        val s = session ?: return
        val r = renderer
        if (r != null) {
            r.withLockedSession { locked -> evalRecorder.stopRecording(locked) }
        } else {
            evalRecorder.stopRecording(s)
        }
    }

    /** A/B switch for the pose fusion (Sub-project B): on = corrected snap-back fusion, off = old toggle. */
    fun evalSetFusionEnabled(on: Boolean) {
        // The INTENT is recorded unconditionally; the renderer is updated if there is one.
        //
        // This used to read the value back — `_evalFusionEnabled.value = renderer?.fusionEnabled ?:
        // false` — so a tap while no renderer was attached silently did nothing AND reported OFF.
        // Entering AR, a surface rebuild, the moments around a target capture: any of them, and the
        // artist turns drift correction on and watches the button say it is off. Reported as "I
        // wasn't the one that turned it off", which is exactly right.
        //
        // I defended the read-back as honest mirroring, and it was the opposite. `attachSessionToRenderer`
        // already pushes this value onto every renderer that attaches, so recording the intent is
        // not a claim about a state that was never applied — it is a promise this class keeps.
        // Dropping the request was the lie.
        _evalFusionEnabled.value = on
        renderer?.fusionEnabled = on
        // Remembered across launches. See SettingsRepository.driftCorrectionEnabled — a field run
        // enabled this, ended, and silently came back off, and the next session discarded 241 good
        // relocalizations without a word.
        viewModelScope.launch { settingsRepository.setDriftCorrectionEnabled(on) }
    }

    /**
     * Restores both experiment switches from storage.
     *
     * Only the view model's intent is set here — `attachSessionToRenderer` is what pushes it onto a
     * renderer, and it runs on every attach, so a restore that lands before the renderer exists is
     * still honoured.
     */
    private fun restoreExperimentSwitches() {
        // firstOrNull, not first: `first()` throws NoSuchElementException on a flow that completes
        // without emitting, and this runs in `init`, so the throw would take the whole view model
        // down at construction. A store with nothing in it means "never chosen", which is the
        // default — not an error.
        viewModelScope.launch {
            _evalFusionEnabled.value = settingsRepository.driftCorrectionEnabled.firstOrNull() ?: true
            renderer?.fusionEnabled = _evalFusionEnabled.value
        }
        viewModelScope.launch {
            _evalSelfGrowEnabled.value = settingsRepository.selfGrowEnabled.firstOrNull() ?: true
            slamManager.setSelfGrowEnabled(_evalSelfGrowEnabled.value)
        }
        viewModelScope.launch {
            _evalFeatureMapEnabled.value = settingsRepository.featureMapEnabled.firstOrNull() ?: true
            applyFeatureMapSwitch(_evalFeatureMapEnabled.value)
        }
        viewModelScope.launch {
            // Defaults TRUE (AUTO) — the shipped behaviour — so a store that has never been written
            // does not silently change the focus mode.
            _evalAutoFocusEnabled.value = settingsRepository.autoFocusEnabled.firstOrNull() ?: true
            effectiveAutoFocus = _evalAutoFocusEnabled.value
            renderer?.updateAutoFocus(effectiveAutoFocus)
        }
    }

    /**
     * Push the feature-map switch to the engine. Build and match move together on purpose: matching
     * against a map nothing grows finds nothing, and growing one nothing matches only costs memory.
     */
    private fun applyFeatureMapSwitch(on: Boolean) {
        slamManager.setMapBuildEnabled(on)
        slamManager.setMapRelocEnabled(on)
        applyDepthPlacementSwitch()
    }

    /**
     * Sphere map Phase 2: depth-calibrated radial placement runs only while the map is building AND
     * the "Monocular depth" setting is on — that setting is what decides whether MiDaS depth is used.
     * Pushed to native on either change, so turning the setting off stops depth placement at once
     * even in a standalone analyzer that was started with an estimator (which stops inferring from
     * its next start; see [standaloneDepthEstimatorOrNull]). Inert on the ARCore path, where nothing
     * stashes monocular depth.
     */
    private fun applyDepthPlacementSwitch() {
        slamManager.setDepthPlacementEnabled(
            _evalFeatureMapEnabled.value && _uiState.value.monocularDepthEnabled,
        )
    }

    private val _evalFusionEnabled = MutableStateFlow(true)

    /**
     * The A/B switch's ACTUAL position, mirrored rather than shadowed.
     *
     * The eval panel held this in a `remember`, seeded with a guess at the renderer's default. The
     * guess went stale and the button spent 187 commits claiming fusion was on while it was off; the
     * obvious repair — correct the seed — leaves the same defect one rotation away, because a
     * `remember` is dropped on configuration change while `ArRenderer.fusionEnabled` is not.
     *
     * A switch that can disagree with the thing it switches invalidates the comparison it exists to
     * run, so it does not get to hold its own copy. This flow is the INTENT: seeded from the persisted
     * setting (renderer's shipped default, off, when never written), written only through
     * [evalSetFusionEnabled], and pushed onto every renderer that attaches (attachSessionToRenderer),
     * so the renderer is made to match it rather than read back from.
     */
    val evalFusionEnabled: StateFlow<Boolean> = _evalFusionEnabled.asStateFlow()

    private val _evalSelfGrowEnabled = MutableStateFlow(true)

    /** Same arrangement for self-grow, which had the identical shadowing problem. */
    val evalSelfGrowEnabled: StateFlow<Boolean> = _evalSelfGrowEnabled.asStateFlow()

    /**
     * A/B switch for teleological self-grow (default ON, from SettingsRepository.selfGrowEnabled):
     * promote validated new marks into the doodle.
     */
    fun evalSetSelfGrowEnabled(on: Boolean) {
        slamManager.setSelfGrowEnabled(on)
        _evalSelfGrowEnabled.value = on
        viewModelScope.launch { settingsRepository.setSelfGrowEnabled(on) }
    }

    private val _evalFeatureMapEnabled = MutableStateFlow(true)

    /** Same arrangement again for the persistent wall feature map (phases 2b/3). */
    val evalFeatureMapEnabled: StateFlow<Boolean> = _evalFeatureMapEnabled.asStateFlow()

    /**
     * A/B switch for the persistent wall feature map (default ON, from
     * SettingsRepository.featureMapEnabled): grow it from locked frames and
     * match against it. Both native flags had no caller before this, so the map — and the `.gxr`
     * persistence built for it — could never do anything.
     */
    fun evalSetFeatureMapEnabled(on: Boolean) {
        applyFeatureMapSwitch(on)
        _evalFeatureMapEnabled.value = on
        viewModelScope.launch { settingsRepository.setFeatureMapEnabled(on) }
    }

    /**
     * Per-keyframe device-orientation log for the spherical-coverage map (Phase 1b,
     * docs/SPHERESLAM_SPHERE_MAP.md). Accumulated during standalone tracking, snapshotted onto the
     * project in [saveProjectWallMap], co-registered with the wall feature map and reset on the same
     * canonical-frame boundaries. Storage only — nothing consumes it for relocalization yet.
     */
    private val keyframeOrientationRecorder = KeyframeOrientationRecorder()

    /**
     * Absolute camera attitude source (heading + elevation) for the sweep coverage, from SphereSLAM.
     * Lazily created; sampling runs while the standalone AR composition is resumed
     * ([startStandaloneCameraAttitude]) and stops on pause/exit. It is base-photosphere input, not
     * gated by the feature-map flag. Magnetometer-fused (`TYPE_ROTATION_VECTOR`), so both axes are
     * drift-free. Since SphereSLAM 0.24.3 it is the ONE standalone attitude sensor: keyframe
     * headings, the tracking session's `Frame.attitude` ([latestStandaloneAttitudeSample]), the
     * display-rate coverage glow and the artwork pose predictor ([standaloneAttitudeExtrapolator]).
     */
    private val cameraAttitudeProvider by lazy {
        com.hereliesaz.sphereslam.CameraAttitudeProvider(appContext)
    }

    /**
     * Base SphereSLAM photosphere. It exists independently of doodles/teleological SLAM.
     *
     * CameraX visual keyframes populate this map from standalone startup. Every tile begins
     * `needsUpdate=true`; keyframes advance each tile's scan progress, and a tile becomes current once
     * the keyframes' views have covered it (SphereSLAM 0.25.1 `recordView`). The later doodle is
     * anchored into this already-running map.
     *
     * The library default since 0.25.1 is the whole sphere — every heading and every elevation to the
     * zenith and nadir, 24 × 6 tiles of 15° × 30° — so the ceiling, floor and everything behind the
     * start are scannable.
     */
    private val spherePhotosphere = com.hereliesaz.sphereslam.PhotosphereMap()
    private val spherePhotosphereLock = Any()
    private val _sphereCoverageFraction = MutableStateFlow(0f)

    /** Fraction of photosphere tiles that are current, in `[0, 1]`. */
    val sphereCoverageFraction: StateFlow<Float> = _sphereCoverageFraction.asStateFlow()

    private val _sphereThinDirections =
        MutableStateFlow<List<com.hereliesaz.sphereslam.SphereCoverage.Direction>>(emptyList())

    /** Directions whose photosphere tiles are missing or currently need an update. */
    val sphereThinDirections: StateFlow<List<com.hereliesaz.sphereslam.SphereCoverage.Direction>> =
        _sphereThinDirections.asStateFlow()

    private val _sphereCurrentRegions =
        MutableStateFlow<List<com.hereliesaz.sphereslam.SphereCoverage.TileRegion>>(emptyList())

    /**
     * Angular extents of the photosphere tiles that are current — the holes cut out of the coverage
     * wash ([com.hereliesaz.sphereslam.PhotosphereMap.currentRegions]).
     */
    val sphereCurrentRegions: StateFlow<List<com.hereliesaz.sphereslam.SphereCoverage.TileRegion>> =
        _sphereCurrentRegions.asStateFlow()

    // Seeded from the map at construction. Before the first keyframe anchors the map's wall heading,
    // regionsNeedingUpdate() is EMPTY (every tile needs an update, but none has a direction yet);
    // CoverageGlowStyleMapping.regionsFor reads "no current and no needing-update regions" as "the
    // whole view", so the "missing tiles only" style still glows everywhere until then, like the wash.
    private val _sphereRegionsNeedingUpdate =
        MutableStateFlow(spherePhotosphere.regionsNeedingUpdate())

    /**
     * Angular extents of the photosphere tiles that are missing or stale — the glow geometry for
     * [com.hereliesaz.graffitixr.common.model.CoverageGlowStyle.MISSING_TILES]
     * ([com.hereliesaz.sphereslam.PhotosphereMap.regionsNeedingUpdate]).
     */
    val sphereRegionsNeedingUpdate:
        StateFlow<List<com.hereliesaz.sphereslam.SphereCoverage.TileRegion>> =
        _sphereRegionsNeedingUpdate.asStateFlow()

    private val _sphereRegionsInProgress =
        MutableStateFlow<List<com.hereliesaz.sphereslam.PhotosphereMap.RegionProgress>>(emptyList())

    /**
     * Partly scanned photosphere tiles and how far along each is, in `(0, 1)` — drawn more opaque as
     * they fill, until the tile completes and opens to the camera
     * ([com.hereliesaz.sphereslam.PhotosphereMap.regionsInProgress]).
     */
    val sphereRegionsInProgress:
        StateFlow<List<com.hereliesaz.sphereslam.PhotosphereMap.RegionProgress>> =
        _sphereRegionsInProgress.asStateFlow()

    /**
     * Latest keyframe per photosphere tile (SphereSLAM [com.hereliesaz.sphereslam.PhotosphereKeyframeStore]);
     * feeds [spherePhotosphere], anchors its wall heading on the first keyframe, and supplies the
     * capture view for doodle placement. Guarded by [spherePhotosphereLock] with the map.
     */
    private val spherePhotosphereKeyframes =
        com.hereliesaz.sphereslam.PhotosphereKeyframeStore(spherePhotosphere)
    @Volatile private var standalonePhotosphereActive: Boolean = false
    @Volatile private var sphereFingerprintAnchorTile: com.hereliesaz.sphereslam.TileId? = null

    /**
     * Timestamped attitude history of the standalone attitude sensor (`elapsedRealtimeNanos` base),
     * fed by every sample: the display-rate glow's rotation source and the artwork pose predictor
     * read it on the GL thread. Thread-safe and allocation-free to read.
     */
    val standaloneAttitudeExtrapolator: com.hereliesaz.sphereslam.attitude.AttitudeExtrapolator
        get() = cameraAttitudeProvider.attitudeExtrapolator

    /**
     * Latest body→world sample of the same sensor (defined at the poles, unlike the heading), for the
     * standalone tracking session's `Frame.attitude`; null before the first sample.
     */
    fun latestStandaloneAttitudeSample(): com.hereliesaz.sphereslam.attitude.AttitudeSample? =
        cameraAttitudeProvider.latestSample()

    /**
     * Whether the device has the standalone attitude sensor (`TYPE_ROTATION_VECTOR`). When false
     * ([startStandaloneCameraAttitude] bails), the host must NOT wire [latestStandaloneAttitudeSample]
     * / [standaloneAttitudeExtrapolator] into the tracking session: they would stay empty forever
     * and suppress the analyzer's own game-rotation fallback (no frame attitude, no gyro bridge).
     */
    val isStandaloneAttitudeSensorAvailable: Boolean
        get() = cameraAttitudeProvider.isAvailable

    /**
     * The latest camera attitude for the image [cameraFromDevice] maps the device body into
     * (SphereSLAM `DeviceCameraRotation`): heading, elevation and that image's roll, all from one
     * sample. Stamped on photosphere keyframes and used to place a capture tap; null without a sample.
     */
    fun latestCameraAttitude(cameraFromDevice: FloatArray): StandaloneCameraAttitude? {
        val a = cameraAttitudeProvider.latestAttitude() ?: return null
        return StandaloneCameraAttitude(
            headingDeg = a.headingDeg,
            elevationDeg = a.elevationDeg,
            rollDeg = com.hereliesaz.sphereslam.CameraAttitudeProvider.cameraRollDegrees(a.deviceToWorld, cameraFromDevice),
        )
    }

    /** The latest camera-axis (heading, elevation), from one sample; null without a sample. */
    private val latestCameraAxis: Pair<Float, Float>?
        get() = cameraAttitudeProvider.latestAttitude()?.let { it.headingDeg to it.elevationDeg }

    /** Current display rotation in degrees (0/90/180/270); 0 when the display is unavailable. */
    private fun displayRotationDegrees(): Int = runCatching {
        val display = appContext.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        (display?.rotation ?: android.view.Surface.ROTATION_0) * 90
    }.getOrDefault(0)

    /**
     * Start compass/elevation sampling for the standalone base photosphere. The analyzer captures a
     * photosphere keyframe only when [latestCameraAttitude] is non-null, so without this the map never
     * receives a keyframe, `wallHeading` is never set, and every doodle capture fails closed.
     * Owned by the standalone AR composition (started on entry/resume, stopped on pause/exit).
     */
    fun startStandaloneCameraAttitude() {
        if (!cameraAttitudeProvider.isAvailable) {
            appendDiag("SphereSLAM camera attitude unavailable: no rotation-vector sensor")
            return
        }
        // Game rate (~50-200 Hz), not the provider's UI default (~16 Hz): the glow and the artwork
        // are re-posed from this sensor on every display frame.
        cameraAttitudeProvider.start(android.hardware.SensorManager.SENSOR_DELAY_GAME)
        appendDiag("SphereSLAM camera attitude sampling started rate=GAME")
    }

    /** Release the compass sensor; idempotent. */
    fun stopStandaloneCameraAttitude() {
        cameraAttitudeProvider.stop()
    }

    /** Persist optional atlas-growth orientation diagnostics; base photosphere mapping is separate. */
    fun recordStandaloneKeyframeOrientation(timestampNs: Long, quaternion: FloatArray) {
        if (_evalFeatureMapEnabled.value) {
            keyframeOrientationRecorder.record(timestampNs, quaternion)
        }
    }

    private fun refreshSphereTileGlowLocked() {
        _sphereCoverageFraction.value = spherePhotosphere.coverageFraction()
        _sphereThinDirections.value = spherePhotosphere.directionsNeedingUpdate()
        _sphereCurrentRegions.value = spherePhotosphere.currentRegions()
        _sphereRegionsNeedingUpdate.value = spherePhotosphere.regionsNeedingUpdate()
        _sphereRegionsInProgress.value = spherePhotosphere.regionsInProgress()
    }

    fun onStandalonePhotosphereKeyframe(keyframe: com.hereliesaz.sphereslam.PhotosphereKeyframe) {
        var tile: com.hereliesaz.sphereslam.TileId? = null
        var firstCaptureForTile = false
        synchronized(spherePhotosphereLock) {
            // The store anchors the map's wall heading on the first keyframe: the photosphere owns
            // its coordinate frame from startup. That is NOT a doodle anchor; it is simply the
            // initial camera-facing bearing for the base map.
            spherePhotosphereKeyframes
                .record(keyframe, nowMs = android.os.SystemClock.elapsedRealtime())
                ?.let {
                    tile = it.tile
                    firstCaptureForTile = it.firstCaptureForTile
                }
            refreshSphereTileGlowLocked()
        }

        standalonePhotosphereActive = true
        val state = _uiState.value
        if (state.isArCoreAvailabilityResolved && !state.isArCoreAvailable) {
            val available = state.isSphereSlamAvailable
            publishSphereSlamRuntimeStatus(
                com.hereliesaz.graffitixr.common.model.SphereSlamRuntimeStatus(
                    available = available,
                    mode = if (available) {
                        com.hereliesaz.graffitixr.common.model.SphereSlamRuntimeMode.STANDALONE
                    } else {
                        com.hereliesaz.graffitixr.common.model.SphereSlamRuntimeMode.UNAVAILABLE
                    },
                    active = available,
                    referenceReady = state.isSphereSlamReferenceRegistered,
                    mappingData = true,
                    trackingData = state.sphereSlamRuntimeStatus.trackingData,
                )
            )
        }
        _uiState.update { it.copy(isScanning = true, isArReady = true) }

        if (firstCaptureForTile && tile != null) {
            appendDiag(
                "SphereSLAM photosphere tile scanned sector=${tile!!.sector} band=${tile!!.band} " +
                    "coverage=${"%.3f".format(_sphereCoverageFraction.value)}"
            )
        }
    }

    /**
     * Persist the rigid bridge solved on a frame where the base photosphere pose and precision KPM
     * pose are both known. Empty is the only unresolved sentinel; identity is a legitimate solved
     * transform and must never be used as a fallback.
     */
    private fun sameStandaloneTransform(
        a: List<Float>,
        b: List<Float>,
        epsilon: Float = 1e-5f,
    ): Boolean {
        if (a.size != 16 || b.size != 16) return false
        return a.indices.all { i ->
            a[i].isFinite() && b[i].isFinite() &&
                kotlin.math.abs(a[i] - b[i]) <= epsilon
        }
    }

    private fun sameStandaloneTransform(
        a: FloatArray,
        b: List<Float>,
        epsilon: Float = 1e-5f,
    ): Boolean =
        a.size == 16 &&
            b.size == 16 &&
            a.indices.all { i ->
                a[i].isFinite() && b[i].isFinite() &&
                    kotlin.math.abs(a[i] - b[i]) <= epsilon
            }

    fun onStandaloneMapFromFingerprintSolved(mapFromFingerprint: FloatArray) {
        if (
            mapFromFingerprint.size != 16 ||
            mapFromFingerprint.any { !it.isFinite() }
        ) return

        val starting = projectRepository.currentProject.value ?: return
        if (starting.sphereSlamFingerprint == null) return
        val solved = mapFromFingerprint.toList()
        if (sameStandaloneTransform(solved, starting.sphereSlamMapFromFingerprint)) return

        val projectId = starting.id
        val anchorGeneration = starting.sphereSlamAnchorGeneration
        viewModelScope.launch(dispatchers.io) {
            var committed = false
            projectRepository.updateProject { current ->
                if (
                    current.id != projectId ||
                    current.sphereSlamFingerprint == null ||
                    current.sphereSlamAnchorGeneration != anchorGeneration
                ) {
                    current
                } else if (sameStandaloneTransform(solved, current.sphereSlamMapFromFingerprint)) {
                    current
                } else {
                    committed = true
                    // This is a precision-layer frame refinement only. The continuously-running
                    // SphereSLAM photosphere stays intact. A MobileGS feature map built against the
                    // provisional transform cannot survive the rigid-frame correction, so discard
                    // only that derived precision map; it will rebuild in the solved map frame.
                    current.copy(
                        sphereSlamMapFromFingerprint = solved,
                        sphereSlamWallFeatureMap = null,
                        sphereSlamWallFeatureMapFrameVersion =
                            com.hereliesaz.graffitixr.common.model.SPHERE_SLAM_FINGERPRINT_FRAME_VERSION,
                    )
                }
            }
            if (committed) {
                appendDiag(
                    "SphereSLAM map_from_doodle refined from same-frame base+KPM pose " +
                        "generation=$anchorGeneration"
                )
            }
        }
    }

    private fun currentPhotosphereTile(): com.hereliesaz.sphereslam.TileId? {
        val attitude = latestCameraAxis ?: return null
        return synchronized(spherePhotosphereLock) {
            spherePhotosphere.tileAt(attitude.first, attitude.second)
        }
    }

    private val _evalAutoFocusEnabled = MutableStateFlow(true)

    /**
     * The artist's persisted focus choice (Settings / eval panel): ON is `FocusMode.AUTO`, OFF is
     * `FocusMode.FIXED`. Always equal to what DataStore holds — the automatic acquire-then-lock below
     * changes only [effectiveAutoFocus], never this, so a saved FIXED is not shown as AUTO after an
     * AR exit (nor a saved AUTO as FIXED while an anchor is locked).
     */
    val evalAutoFocusEnabled: StateFlow<Boolean> = _evalAutoFocusEnabled.asStateFlow()

    // The focus mode actually applied to the live session: the preference above, except while the
    // acquire-then-lock has parked an AUTO preference on FIXED for a placed anchor.
    @Volatile private var effectiveAutoFocus = true

    // Once the artist toggles focus by hand, the automatic acquire-then-lock below steps aside for the
    // rest of the session. Reset on AR exit so each session starts with the automatic behavior again.
    @Volatile private var focusManuallyOverridden = false

    // True while the placed anchor is within close range (see CLOSE_TARGET_*), with hysteresis so a
    // target near the boundary doesn't flip focus modes.
    @Volatile private var anchorIsClose = false

    /**
     * Acquire-then-lock autofocus. AUTO while the artist frames the wall — a sharp image is what gives
     * ARCore/KPM the features to acquire — then FIXED once the anchor is placed, so the focal length
     * stops sweeping during tracking. ARCore documents fixed focus as better for tracking and uses it as
     * its own default; AUTO's focal sweeps are a known tracking-instability source. This gets both: a
     * sharp acquisition and stable optics after. A manual focus toggle disables it for the session, and
     * a saved FIXED preference never acquires in AUTO at all. Changes only [effectiveAutoFocus].
     *
     * Exception: a close target (a canvas board or paper under the phone, [anchorIsClose]) keeps AUTO.
     * FIXED parks the lens at infinity, and at arm's length that blurs every frame.
     */
    private fun applyAnchorFocusLock(anchorEstablished: Boolean) {
        if (focusManuallyOverridden) return
        if (!_evalAutoFocusEnabled.value) return
        val auto = !anchorEstablished || anchorIsClose
        if (effectiveAutoFocus == auto) return
        effectiveAutoFocus = auto
        renderer?.updateAutoFocus(auto)
        appendDiag("focus: ${if (auto) "AUTO (acquiring)" else "FIXED (locked on anchor)"}")
    }

    // Monocular depth (MiDaS Small; SphereSLAM :models on LiteRT, GPU delegate with CPU fallback —
    // `activeBackend` says which). Created lazily; only touched when the "Monocular depth" setting is
    // on. Its one consumer is the standalone analyzer's sphere-map radial placement
    // ([standaloneDepthEstimatorOrNull]); selfTestDepth proves it loads and runs on-device.
    private val depthEstimatorLazy = lazy {
        com.hereliesaz.sphereslam.models.DepthEstimator(appContext)
    }
    private val depthEstimator get() = depthEstimatorLazy.value

    /**
     * The shared depth estimator for the standalone analyzer, or null unless BOTH the feature map is
     * building (Phase 2 — depth only calibrates map-point radii, so it has no use without the map)
     * AND the "Monocular depth" setting is on. Returning null keeps the analyzer on the pure
     * wall-plane path; a non-null estimator opts that session into depth-calibrated radial map-point
     * placement. Single instance, closed in [onCleared], so the analyzer must not close it.
     */
    fun standaloneDepthEstimatorOrNull(): com.hereliesaz.sphereslam.models.DepthEstimator? =
        if (_evalFeatureMapEnabled.value && _uiState.value.monocularDepthEnabled) depthEstimator else null

    /** One synthetic-image inference to confirm the depth model loads AND executes on this device. */
    private fun selfTestDepth() {
        if (!depthEstimator.load()) {
            appendDiag("depth: model unavailable — ${depthEstimator.lastError ?: "load failed"}")
            return
        }
        val n = 64
        val probe = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
        for (y in 0 until n) for (x in 0 until n) {
            val v = ((x + y) * 2).coerceAtMost(255)
            probe.setPixel(x, y, android.graphics.Color.rgb(v, v, v))
        }
        val t0 = android.os.SystemClock.elapsedRealtime()
        val map = depthEstimator.estimate(probe)
        probe.recycle()
        if (map == null) {
            appendDiag("depth: inference failed")
            return
        }
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        var sum = 0.0
        for (v in map.data) {
            if (v < mn) mn = v
            if (v > mx) mx = v
            sum += v
        }
        val ms = android.os.SystemClock.elapsedRealtime() - t0
        appendDiag(
            "depth self-test: ${map.width}x${map.height} min=%.3f max=%.3f mean=%.3f %dms backend=%s"
                .format(mn, mx, sum / map.data.size, ms, depthEstimator.activeBackend ?: "unknown"),
        )
    }

    /**
     * A/B switch for the camera focus mode, applied to the LIVE session so the two can be compared
     * on the same wall without re-entering AR.
     *
     * This is the one AR switch that changes what ARCore itself sees rather than what we do with the
     * result: AUTO's focal-length sweeps are a known source of tracking instability on devices with
     * sloppy intrinsics, and FIXED's infinity lens is unusable at arm's length in low light. Neither
     * is correct in general, which is why it is a switch and not a fix.
     */
    fun evalSetAutoFocusEnabled(on: Boolean) {
        focusManuallyOverridden = true
        effectiveAutoFocus = on
        renderer?.updateAutoFocus(on)
        _evalAutoFocusEnabled.value = on
        viewModelScope.launch { settingsRepository.setAutoFocusEnabled(on) }
    }

    /**
     * The pasteable report: what ran, what it measured, and under which constants.
     *
     * Assembled here rather than in the recorder because the recorder deliberately knows nothing
     * about Android or about this project's tunables — it holds samples. The build, the device and
     * the parameter values live on this side.
     */
    fun buildDiagnosticReport(): String = diagnosticRecorder.report(
        header = linkedMapOf(
            "build" to (if (BuildConfig.DEBUG) "debug" else "release"),
            "commit" to BuildConfig.GIT_COMMIT,
            "device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "android" to (android.os.Build.VERSION.RELEASE ?: "unknown"),
            "session" to "${(System.currentTimeMillis() - diagnosticSessionStartMs) / 1000}s since AR view-model start",
            "sphereSlamAvailable" to _uiState.value.sphereSlamRuntimeStatus.available.toString(),
            "sphereSlamMode" to _uiState.value.sphereSlamRuntimeStatus.mode.name,
            "sphereSlamActive" to _uiState.value.sphereSlamRuntimeStatus.active.toString(),
            "sphereSlamReferenceReady" to _uiState.value.sphereSlamRuntimeStatus.referenceReady.toString(),
            "sphereSlamMappingData" to _uiState.value.sphereSlamRuntimeStatus.mappingData.toString(),
            "sphereSlamTrackingData" to _uiState.value.sphereSlamRuntimeStatus.trackingData.toString(),
            // Two flags that change what every number below MEANS, so they belong beside the numbers
            // and not in a settings screen the reader cannot see. Read back from the objects that
            // hold them rather than from whatever the eval buttons last asked for.
            "fusion" to (renderer?.fusionEnabled?.toString() ?: "no renderer attached"),
            "syncReloc" to slamManager.evalSyncRelocEveryN().let { if (it > 0) "every $it" else "async" },
            // The reason an empty-doodle run is empty. Without it the report can say the
            // doodle is missing and nothing about why, which is where three device runs stalled.
            "lastCapture" to (com.hereliesaz.graffitixr.feature.ar.anchor.MetricDoodleBuilder
                .lastRefusalReason?.let { "REFUSED — $it" } ?: "no refusal recorded"),
            // The single most direct test for "the overlay is receding": is the ARCore anchor's OWN
            // pose moving, independent of fusion, relocalization, or the consensus average. A device
            // run went 6.2 -> 16.0 ft in four seconds with fusion off and no doodle — nothing
            // downstream of the anchor was even running — traced to the primary anchor being attached
            // to a still-forming ARCore Plane trackable rather than a fixed world pose, so ARCore's
            // own plane refinement dragged it along. Fixed by anchoring to a fixed pose instead; this
            // number is what proves whether that fix held on the NEXT run, rather than requiring a
            // burst of screenshots and a stopwatch again.
            "anchorDriftM" to (renderer?.primaryAnchorDriftMeters()?.let {
                if (it >= 0f) String.format(java.util.Locale.US, "%.3f", it) else "no anchor established"
            } ?: "no renderer attached"),
        ),
        parameters = diagnosticParameters(),
        captures = captureManifest.toList(),
        capturesTruncated = diagnosticWatcher.atCap,
    )

    /**
     * Every tunable the report's numbers were produced under.
     *
     * `PARAMETERS.md`'s point is that these are pre-registered priors no experiment has moved, so a
     * report of behaviour without them cannot be acted on — and the person reading it is usually not
     * the person holding the phone, and cannot check out the commit to look them up.
     *
     * The Kotlin constants are read live. The native ones are not reachable from here without a JNI
     * accessor per constant, so they are named with the header that defines them: a reader who needs
     * the value knows exactly where to look, and nothing here can drift into claiming a value the
     * engine does not hold.
     */
    private fun diagnosticParameters(): Map<String, String> {
        val fusion = com.hereliesaz.graffitixr.feature.ar.anchor.PoseFusion
        val gate = com.hereliesaz.graffitixr.feature.ar.anchor.PromotionGate
        val radius = com.hereliesaz.graffitixr.feature.ar.anchor.SearchRadius
        return linkedMapOf(
            "PoseFusion.MIN_INLIER_RATIO" to fusion.MIN_INLIER_RATIO.toString(),
            "PoseFusion.BASE_ALPHA" to fusion.BASE_ALPHA.toString(),
            "PoseFusion.CONF_FLOOR" to fusion.CONF_FLOOR.toString(),
            "PoseFusion.COLD_SNAP_INLIER_RATIO" to fusion.COLD_SNAP_INLIER_RATIO.toString(),
            "PoseFusion.COLD_SNAP_MIN_INLIERS" to fusion.COLD_SNAP_MIN_INLIERS.toString(),
            "PoseFusion.COLD_SNAP_DIST_M" to fusion.COLD_SNAP_DIST_M.toString(),
            "PoseFusion.COLD_SNAP_ANGLE_DEG" to fusion.COLD_SNAP_ANGLE_DEG.toString(),
            "PromotionGate.BIG_LOCK_INLIERS" to gate.BIG_LOCK_INLIERS.toString(),
            "PromotionGate.MIN_INLIERS" to gate.MIN_INLIERS.toString(),
            "PromotionGate.MIN_INLIER_RATIO" to gate.MIN_INLIER_RATIO.toString(),
            "PromotionGate.MIN_INLIER_SPREAD" to gate.MIN_INLIER_SPREAD.toString(),
            "SearchRadius.RHO" to radius.RHO.toString(),
            "SearchRadius.MIN_PX" to radius.MIN_PX.toString(),
            "SearchRadius.MAX_PX" to radius.MAX_PX.toString(),
            "SearchRadius.ERR_GAIN" to radius.ERR_GAIN.toString(),
            "SearchRadius.REPROJ_GAIN" to radius.REPROJ_GAIN.toString(),
            "DiagnosticWatcher.SPIKE_MM" to
                com.hereliesaz.graffitixr.feature.ar.eval.DiagnosticWatcher.SPIKE_MM.toString(),
            "native kRelocLoweRatio" to "(MobileGS.h)",
            "native kCorrobLoweRatio" to "(MobileGS.h)",
            "native kCorrobConfirmations" to "(MobileGS.h)",
        )
    }

    /**
     * Writes the report and returns every file worth attaching, report first.
     *
     * Ordered so the reader opens the report before the raw artefacts, and so a share sheet that
     * only previews the first item previews the thing meant to be read. Anything absent is simply
     * not in the list — a missing CSV means no eval run was logged, which the report already says
     * in words.
     */
    fun writeDiagnosticBundle(): List<File> {
        val dir = File(appContext.filesDir, "eval").apply { mkdirs() }
        val report = File(dir, "diagnostic_report.md")
        report.writeText(buildDiagnosticReport())
        val out = ArrayList<File>()
        out.add(report)
        // Newest first: a session usually has one run that matters and it is the last one.
        dir.listFiles { f -> f.isFile && (f.name.endsWith(".csv") || f.name.endsWith(".run.json")) }
            ?.sortedByDescending { it.lastModified() }
            ?.take(MAX_BUNDLED_EVAL_FILES)
            ?.let(out::addAll)
        diagnosticCapturesDir().listFiles { f -> f.isFile && f.name.endsWith(".png") }
            ?.sortedBy { it.name }
            ?.let(out::addAll)
        return out
    }

    /**
     * Where auto-captured screenshots land, beside the eval CSVs they are shared with.
     *
     * They are reachable by `FileProvider` only because `file_paths.xml` exposes `filesDir/eval/`
     * (`<files-path path="eval/"/>`, which covers `eval/captures/` too) — moving this directory out
     * of `eval/` would make the bundle's attachments fail to resolve, so change both together.
     *
     * **Purged once per view-model lifetime**, on first use. Without it the directory accumulates
     * across every session the app has ever run, and [writeDiagnosticBundle] — which globs the
     * directory rather than reading the manifest — would attach a dozen screenshots from last week
     * to a report whose Screenshots section lists one. Wrong attachments are worse than none: the
     * reader has no way to tell which frame belongs to the run they are being asked about.
     *
     * The manifest is the record of what this session took; the directory is made to agree with it.
     */
    fun diagnosticCapturesDir(): File {
        val dir = File(appContext.filesDir, "eval/captures").apply { mkdirs() }
        if (capturesPurged.compareAndSet(false, true)) {
            // At most MAX_CAPTURES small files, once per session. Cheap enough not to be worth
            // dispatching, and every caller either already writes to this directory or is about to.
            dir.listFiles()?.forEach { it.delete() }
        }
        return dir
    }

    // ── Glasses session lifecycle ─────────────────────────────────────────────

    fun startGlassesSession() {
        if (
            _uiState.value.isArCoreAvailabilityResolved &&
            !_uiState.value.isArCoreAvailable
        ) {
            _glassesSessionState.value =
                GlassesSessionState.Fallback("Standalone wall calibration for glasses is not implemented yet")
            return
        }
        // The Meta wearables SDK (mwdat-camera) requires API 29+. The library
        // is overridden in the manifest so the app can install on API 26+, but
        // we must avoid invoking it on those devices at runtime.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            _glassesSessionState.value =
                GlassesSessionState.Fallback("Glasses require Android 10 or newer")
            return
        }
        val provider = wearableManager.listProviders().firstOrNull { it.name.contains("Meta") }
            ?: run {
                _glassesSessionState.value = GlassesSessionState.Fallback("no Meta provider")
                return
            }
        _glassesSessionState.value = GlassesSessionState.PairingPrompt
        wearableManager.activate(provider)
        slamManager.startSensorCollection()
        viewModelScope.launch {
            provider.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        _glassesSessionState.value = GlassesSessionState.CalibrationPrompt(progress = 0f)
                        synchronized(calibrationLock) {
                            calibrationSrcPoints.clear()
                            calibrationDstPoints.clear()
                        }
                    }
                    is ConnectionState.Disconnected,
                    is ConnectionState.Error -> {
                        val current = _glassesSessionState.value
                        if (current is GlassesSessionState.Active ||
                            current is GlassesSessionState.CalibrationPrompt) {
                            _glassesSessionState.value = GlassesSessionState.Fallback(
                                (state as? ConnectionState.Error)?.message ?: "disconnected"
                            )
                        }
                    }
                    else -> { /* Connecting — no-op */ }
                }
            }
        }
    }

    fun endGlassesSession() {
        wearableManager.deactivate()
        slamManager.stopSensorCollection()
        phoneToGlassesXform = null
        synchronized(calibrationLock) {
            calibrationSrcPoints.clear()
            calibrationDstPoints.clear()
        }
        _glassesSessionState.value = GlassesSessionState.Idle
    }

    fun submitCalibrationTap(screenPoint: PointF) {
        if (
            _uiState.value.isArCoreAvailabilityResolved &&
            !_uiState.value.isArCoreAvailable
        ) {
            _glassesSessionState.value =
                GlassesSessionState.Fallback("Standalone wall calibration for glasses is not implemented yet")
            return
        }
        viewModelScope.launch {
            val phonePoint = arCoreHitTestToWorld(screenPoint) ?: return@launch
            val glassesPoint = glassesWorldHitForTimestamp(System.nanoTime(), screenPoint) ?: return@launch
            val (progress, shouldFinalize) = synchronized(calibrationLock) {
                calibrationSrcPoints.add(phonePoint)
                calibrationDstPoints.add(glassesPoint)
                val count = calibrationSrcPoints.size
                (count / 20f).coerceAtMost(1f) to (count >= 20)
            }
            _glassesSessionState.value = GlassesSessionState.CalibrationPrompt(progress)
            if (shouldFinalize) finalizeCalibration()
        }
    }

    fun recalibrate() {
        synchronized(calibrationLock) {
            calibrationSrcPoints.clear()
            calibrationDstPoints.clear()
        }
        _glassesSessionState.value = GlassesSessionState.CalibrationPrompt(progress = 0f)
    }

    fun applyPhoneToGlasses(point: Vec3): Vec3 = phoneToGlassesXform?.apply(point) ?: point

    private fun resetCalibrationPoints() {
        synchronized(calibrationLock) {
            calibrationSrcPoints.clear()
            calibrationDstPoints.clear()
        }
    }

    private fun finalizeCalibration() {
        // Snapshot under the lock so a concurrent clear (disconnect / recalibrate)
        // can't mutate the lists while Procrustes is solving.
        val (src, dst) = synchronized(calibrationLock) {
            calibrationSrcPoints.toList() to calibrationDstPoints.toList()
        }
        val xform = Procrustes.solve(src, dst) ?: run {
            Timber.w("ArViewModel.finalizeCalibration: Procrustes failed; resetting calibration")
            resetCalibrationPoints()
            _glassesSessionState.value = GlassesSessionState.CalibrationPrompt(progress = 0f)
            return
        }
        if (kotlin.math.abs(xform.approximateScale() - 1f) > 0.05f) {
            Timber.w("ArViewModel.finalizeCalibration: scale mismatch ${xform.approximateScale()}; resetting")
            resetCalibrationPoints()
            _glassesSessionState.value = GlassesSessionState.CalibrationPrompt(progress = 0f)
            return
        }
        phoneToGlassesXform = xform
        _glassesSessionState.value = GlassesSessionState.Active
    }

    /**
     * ARCore-based hit test in screen coordinates → world point. Routed through
     * [com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer.requestHitTest] so the actual
     * Frame.hitTest executes on the GL thread under the renderer's session lock — calling it
     * here directly raced session.update() (ARCore is not thread-safe; same defect class as the
     * flashlight configure() crash). Returns null if no renderer is attached, the render loop
     * doesn't serve the request within 1s (paused), or no plane/point is hit.
     */
    private suspend fun arCoreHitTestToWorld(screenPoint: PointF): Vec3? {
        val r = renderer ?: return null
        val t = kotlinx.coroutines.withTimeoutOrNull(1000L) {
            r.requestHitTest(screenPoint.x, screenPoint.y).await()
        } ?: return null
        return Vec3(t[0], t[1], t[2])
    }

    /**
     * Glasses-frame point for the moment of [timestampNs]. Without a real
     * glasses-side feature-extraction pipeline (camera→world projection in
     * the glasses' own SLAM frame), this hit-tests the SAME [screenPoint] as the
     * phone tap so the src/dst point pairs actually correspond. When glasses-side
     * world lookup is wired, replace this with a real lookup keyed on [timestampNs].
     */
    private suspend fun glassesWorldHitForTimestamp(timestampNs: Long, screenPoint: PointF): Vec3? {
        // Stand-in: hit-test the same screen point as the phone tap. Using screen-center for
        // every dst made the dst cloud degenerate and Procrustes produced a bogus rotation.
        return arCoreHitTestToWorld(screenPoint)
    }

    // ─────────────────────────────────────────────────────────────────────────

    private val _isCameraInUseByAr = MutableStateFlow(false)
    val isCameraInUseByAr = _isCameraInUseByAr.asStateFlow()

    private var isActivityResumed = false
    @Volatile private var isInArMode = false
    private var isSessionResumed = false
    @Volatile private var isDestroying = false
    private val sessionMutex = Mutex()

    /**
     * Where ARCore teardown runs. NOT viewModelScope: onCleared (and MainActivity.onDestroy on a
     * finishing activity) run after that scope is cancelled, so a teardown launched there never
     * executed and Session.close() was silently skipped — leaking the camera into the next launch.
     * A SupervisorJob that nothing cancels lets the close finish after the ViewModel is gone; the work
     * runs on the default dispatcher because Session.close() blocks until an in-flight update() returns.
     */
    private val teardownScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + dispatchers.default +
            kotlinx.coroutines.CoroutineName("ArSessionTeardown"),
    )

    // Set in onCleared: no teardown may schedule a fresh session for a dead ViewModel.
    @Volatile private var isCleared = false

    // Timestamps used to flip `trackingFailed` when ARCore never reaches a
    // TRACKING state within the grace window after entering AR mode. Reset on
    // every AR-mode entry; updated each time setTrackingState reports tracking.
    @Volatile private var arEntryTimestampMs: Long = 0L
    @Volatile private var lastTrackingTimestampMs: Long = 0L
    /** ARCore has tracked at least once since this AR entry: whatever comes after is a loss, not a broken start. */
    @Volatile private var trackedSinceArEntry: Boolean = false
    private val trackingFailureGraceMs: Long = 10_000L
    // Forced-stereo "stuck" detection must beat the 5s input-timeout ANR, so it's much shorter
    // than the general tracking-failure grace.
    private val STEREO_STUCK_GRACE_MS: Long = 3_000L

    private val isSaving = AtomicBoolean(false)
    // Point count of the MobileGS wall feature map (slamManager.getMapPointCount) as of the last
    // successful save — the map saveMapBlocking actually persists. The autosave compares the SAME
    // counter against this, so it only rewrites when that map has grown. (It once held the deleted
    // splat count — a hardcoded 0, so autosave never fired — and later was compared against the
    // ARCore point cloud, a different, unpersisted, much larger number, so the delta was always over
    // the threshold and the project was rewritten every 30 s for the whole session.)
    private val lastSavedMapPointCount = AtomicInteger(0)
    private val lastSavedPaintCount = AtomicInteger(0)
    /** The project native was last loaded for, kept so a switch can save it after the repository moved on. */
    @Volatile private var lastLoadedProjectSnapshot: com.hereliesaz.graffitixr.common.model.GraffitiProject? = null
    /** The Doodle's row count at the last save: it only grows (self-grow), so a rise is unsaved growth. */
    private val lastSavedWallRows = AtomicInteger(0)
    @Volatile private var lastSavedProgress = 0f
    private var autoSaveJob: kotlinx.coroutines.Job? = null
    private var loadedProjectId: String? = null

    // Whose state native MobileGS holds. `loadedProjectId` flips on emission, but native keeps the
    // previous project's map until loadDoodleIfExists finishes swapping it, so saves gate on
    // THIS token instead (see NativeStateOwner).
    private val nativeStateOwner = NativeStateOwner()

    @Volatile
    private var pendingTapPosition: Pair<Float, Float>? = null

    /**
     * Pixel size of the camera view [pendingTapPosition] was measured in, or null when the caller
     * did not supply one. Standalone placement needs it to undo the preview's FILL_CENTER crop.
     */
    @Volatile
    private var pendingTapViewSize: Pair<Int, Int>? = null

    @Volatile
    private var pendingStandaloneFingerprintPlacement: StandaloneFingerprintPlacement? = null

    private val visitedSectors = BooleanArray(36) // 10 degree sectors for higher resolution feedback

    // Declared ABOVE the init block below, on purpose. That block launches coroutines on IO, and
    // viewModelScope's Main.immediate runs the rest synchronously inside the constructor, so code it
    // reaches can execute before any property declared further down the class is initialized. A
    // non-null holder read that early is null: monocularDepthMutex was, and the 1.48.44.1 startup
    // crash was its NPE in reconcileMonocularDepth. (A `= null` initializer is harmless either way:
    // Kotlin emits none for a default value. A non-default one, like `= -1`, would overwrite.)

    // ArAvailabilityChecker's verdict, resolved once per process (a later ARCore install isn't
    // auto-detected). Cached so a "Use ARCore" toggle re-derives the backend without re-running the
    // checker, and so monocular depth knows whether ARCore depth can be the source. Null until resolved.
    @Volatile private var arCoreCheckResult: ArAvailabilityChecker.Result? = null

    // Serializes monocular-depth load/unload so rapid toggles can't interleave a self-test and a close.
    private val monocularDepthMutex = Mutex()

    /** Loader runs between their start and their `finally`; see [loadDoodleIfExists]. */
    private val doodleLoadsInFlight = java.util.concurrent.atomic.AtomicInteger(0)

    // A page frozen this session, waiting for ITS doodle to commit. Guards [pendingHybridPage]
    // and its capture keys: written from the GL thread (renderer callbacks) and read from the
    // project collector.
    private val hybridLock = Any()

    // System-throttle monitoring (see startSystemThrottleMonitoring), declared above the init block
    // that starts it. Declared further down, a recomputeSystemThrottle() during construction read
    // batteryPct as 0 (a real 0%, so the lowest battery tier) instead of -1 (unknown), and the tier
    // thresholds as 0, until a later recompute. (The `= null` holders were never at risk: Kotlin
    // emits no initializer for a default value, so the registration init makes is kept.)
    private var thermalListener: android.os.PowerManager.OnThermalStatusChangedListener? = null
    private var powerReceiver: android.content.BroadcastReceiver? = null
    @Volatile private var thermalHot = false
    @Volatile private var batteryLow = false
    /** Live battery level [0..100], or -1 when unknown. Sampled from ACTION_BATTERY_CHANGED. */
    @Volatile private var batteryPct = -1
    /** Battery-level thresholds (%) for the medium / low degradation tiers. */
    private val BATTERY_TIER_MED = 30
    private val BATTERY_TIER_LOW = 15

    init {
        NativeLibLoader.loadAll()
        startSystemThrottleMonitoring()
        restoreExperimentSwitches()
        viewModelScope.launch(Dispatchers.IO) {
            slamManager.loadSuperPoint(appContext.assets)
            slamManager.loadDistortionHead(appContext.assets) // optional; inert if asset absent
            slamManager.loadLowLightEnhancer(appContext.assets)
            // Monocular depth is loaded/self-tested by reconcileMonocularDepth() once ARCore
            // availability resolves below, and again whenever one of its three settings changes.
        }
        viewModelScope.launch {
            // Resolve ARCore availability up front. With com.google.ar.core
            // marked optional in the manifest, Play does not filter for ARCore
            // devices, so we may be running on hardware that ARCore does not
            // support. The result drives mode-chooser visibility and the
            // first-launch "AR unavailable" onboarding step.
            val result = ArAvailabilityChecker.check(appContext)
            arCoreCheckResult = result
            // Only a fully INSTALLED ARCore routes into the ARCore Session path. A device that is
            // ARCore-capable but has no Google Play Services for AR installed (or too old an APK) reports
            // NeedsInstallOrUpdate — and nothing in this app ever calls ArCoreApk.requestInstall, so
            // treating that as "available" opened an ARCore Session on a device with no ARCore runtime.
            // That session never received a camera frame and surfaced "Camera isn't delivering frames".
            // Those devices take the CameraX + SphereSLAM standalone path instead, which needs no ARCore
            // APK — the app's documented fallback ("unsupported devices remain in AR mode and take the
            // CameraX + SphereSLAM standalone path"). A later install isn't auto-detected mid-process,
            // which is acceptable: standalone AR is fully functional without ARCore.
            // The user can force the standalone path even on an ARCore-capable device (Settings →
            // "Use ARCore"). Treat an opt-out exactly like unsupported hardware: same doodle
            // cleanup, same standalone routing. The checker result is cached above and the backend is
            // re-derived on every toggle, so a change switches backends immediately — mid-AR too.
            Timber.i("ArCore availability resolved: $result")
            settingsRepository.arCoreEnabled.distinctUntilChanged().collect { arCoreOptIn ->
                applyArCoreBackendSelection(result, arCoreOptIn)
            }
        }
        viewModelScope.launch(dispatchers.io) {
            // ARCore and SphereSLAM are independent optional capabilities. In particular, a phone
            // reporting ARCore unsupported must not be routed into standalone AR merely because the
            // Kotlin classes are present: stripped/dev packaging can omit the native KPM objects.
            //
            // The public runtime probe verifies both native availability and calibrated KPM session
            // creation/destruction. A partially packaged binary therefore still fails closed without
            // reaching SphereSLAM's internal diagnostic/test hooks.
            val available = com.hereliesaz.sphereslam.SphereSlam.isOperational()

            _uiState.update {
                it.copy(
                    isSphereSlamAvailable = available,
                    isSphereSlamAvailabilityResolved = true,
                )
            }
            Timber.i("SphereSLAM/KPM availability resolved: available=$available")
            refreshSphereSlamRuntimeSelection()
        }
        viewModelScope.launch {
            projectRepository.currentProject.collect { project ->
                _uiState.update {
                    val nextReferenceUri = project?.sphereSlamReferenceUri
                    val referenceChanged = it.sphereSlamReferenceUri != nextReferenceUri
                    it.copy(
                        sphereSlamReferenceUri = nextReferenceUri,
                        isSphereSlamReferenceRegistered =
                            if (referenceChanged) false else it.isSphereSlamReferenceRegistered,
                        sphereSlamReferenceWidthMeters =
                            project?.sphereSlamReferenceWidthMeters ?: 1f,
                        sphereSlamReferencePhysicallyMetric =
                            project?.sphereSlamReferencePhysicallyMetric ?: false,
                        sphereSlamAnchorGeneration = project?.sphereSlamAnchorGeneration ?: 0L,
                        sphereSlamAnchorFrameVersion =
                            project?.sphereSlamAnchorFrameVersion
                                ?: com.hereliesaz.graffitixr.common.model.SPHERE_SLAM_FINGERPRINT_FRAME_VERSION,
                        sphereSlamPlacementAnchorGeneration =
                            project?.sphereSlamPlacementAnchorGeneration ?: 0L,
                        sphereSlamFingerprint = project?.sphereSlamFingerprint,
                        sphereSlamFingerprintFrameVersion =
                            project?.sphereSlamFingerprintFrameVersion
                                ?: com.hereliesaz.graffitixr.common.model.SPHERE_SLAM_FINGERPRINT_FRAME_VERSION,
                        sphereSlamMapFromFingerprint =
                            project?.sphereSlamMapFromFingerprint.orEmpty(),
                        sphereSlamWallFeatureMap = project?.sphereSlamWallFeatureMap,
                        sphereSlamWallFeatureMapFrameVersion =
                            project?.sphereSlamWallFeatureMapFrameVersion
                                ?: com.hereliesaz.graffitixr.common.model.SPHERE_SLAM_FINGERPRINT_FRAME_VERSION,
                        sphereSlamAtlasPages = project?.sphereSlamAtlasPages.orEmpty(),
                        wallWidthMeters = project?.wallWidthMeters,
                    )
                }
                if (
                    project != null &&
                    _uiState.value.coopRole ==
                        com.hereliesaz.graffitixr.common.model.CoopRole.HOST
                ) {
                    val hosted = hostedCoopSpatialFrame
                    if (hosted != null) {
                        val current = CoopSpatialFrameFactory.fromProject(
                            project,
                            hosted.hostBackend ==
                                com.hereliesaz.graffitixr.common.model.CoopTrackingBackend.ARCORE,
                        )?.copy(doodleAvailable = hosted.doodleAvailable)
                        if (current != hosted) {
                            _feedback.tryEmit(
                                com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(
                                    appContext.getString(DesignR.string.ar_feedback_wall_doodle_changed)
                                )
                            )
                            leaveSession()
                        }
                    }
                }
                if (project != null) {
                    // A restored hybrid page belongs to its project; drop it on a switch (including
                    // to a co-op spectator project, which never offers a replacement).
                    if (project.id != loadedProjectId) {
                        // Native still holds the outgoing project's paint, grown Doodle and map, and
                        // the loader below is about to replace them: write them to that project first.
                        val outgoing = lastLoadedProjectSnapshot
                        if (outgoing != null && outgoing.id == loadedProjectId) {
                            try {
                                withContext(dispatchers.io) { saveProjectWallMap(outgoing) }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Timber.e(e, "Saving the outgoing project's AR state failed")
                            }
                        }
                        renderer?.restoreHybridPage(null)
                        // The yaw sweep and the COMPLETE latch describe the previous project's wall.
                        if (loadedProjectId != null) resetScanProgress()
                    }
                    // BEFORE anything can be written: native still holds the previous project's state.
                    nativeStateOwner.beginSwitch(project.id)
                    loadedProjectId = project.id
                    lastLoadedProjectSnapshot = project
                    // Native MobileGS has exactly one active doodle frame. Standalone owns it
                    // on non-ARCore devices; an ARCore co-op GUEST also owns it once protocol-v3 peer
                    // geometry has been installed. Never let a later spectator-project emission run
                    // the ordinary local loader over that peer doodle.
                    restoreLocalArCoreProjectState(project)
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.ambientScanEnabled.collect { enabled ->
                applyAmbientScanEnabled(enabled)
            }
        }
        viewModelScope.launch {
            // Pass B's switch. The stored value is the only source; it defaults to off.
            settingsRepository.changePassEnabled.collect { on ->
                _uiState.update { it.copy(changePassEnabled = on) }
                slamManager.setChangePassEnabled(on)
            }
        }
        viewModelScope.launch {
            // Camera target fps. Stored in state for the session-config selection; a change while an
            // ARCore session is live swaps its camera config in place (reconcileLiveSessionSettings).
            settingsRepository.cameraTargetFps.collect { fps ->
                _uiState.update { it.copy(cameraTargetFps = fps) }
                reconcileLiveSessionSettings(checkCameraFps = true)
            }
        }
        viewModelScope.launch {
            // Use-ARCore toggle, mirrored into state for the settings UI. The backend switch itself is
            // driven by the availability collector above, which re-derives isArCoreAvailable.
            settingsRepository.arCoreEnabled.collect { on ->
                _uiState.update { it.copy(arCoreEnabled = on) }
            }
        }
        viewModelScope.launch {
            // ARCore depth: reconfigures a live session's depth mode immediately, and decides whether
            // monocular depth has to stand in for it.
            settingsRepository.arCoreDepthEnabled.collect { on ->
                _uiState.update { it.copy(arCoreDepthEnabled = on) }
                reconcileLiveSessionSettings(checkDepth = true)
                reconcileMonocularDepth()
            }
        }
        viewModelScope.launch {
            settingsRepository.monocularDepthEnabled.collect { on ->
                _uiState.update { it.copy(monocularDepthEnabled = on) }
                applyDepthPlacementSwitch()
                reconcileMonocularDepth()
            }
        }
        viewModelScope.launch {
            settingsRepository.throttleOnThermal.collect { on ->
                _uiState.update { it.copy(throttleOnThermal = on) }
                recomputeSystemThrottle()
            }
        }
        viewModelScope.launch {
            settingsRepository.throttleOnPowerSave.collect { on ->
                _uiState.update { it.copy(throttleOnPowerSave = on) }
                recomputeSystemThrottle()
            }
        }
        viewModelScope.launch {
            settingsRepository.throttleOnLowBattery.collect { on ->
                _uiState.update { it.copy(throttleOnLowBattery = on) }
                recomputeSystemThrottle()
                // The battery tier caps a live ARCore camera to 30 fps (effectiveCameraTargetFps), so
                // the toggle must reach the running session, as the Adaptive rate toggle does.
                reconcileLiveSessionSettings(checkCameraFps = true)
            }
        }
        viewModelScope.launch {
            // Lag trigger is evaluated in the renderer; this only forwards the enable toggle.
            settingsRepository.throttleOnLag.collect { on ->
                _uiState.update { it.copy(throttleOnLag = on) }
            }
        }
        viewModelScope.launch {
            // Master adaptive-rate toggle; the renderer reads it to enable/disable idle gating.
            settingsRepository.adaptiveRateEnabled.collect { on ->
                _uiState.update { it.copy(adaptiveRateEnabled = on) }
                // It also caps the camera to 30fps under battery pressure, so a toggle can change the
                // live session's camera config too.
                reconcileLiveSessionSettings(checkCameraFps = true)
            }
        }
        viewModelScope.launch {
            settingsRepository.forcedStereoUnstable.collect { unstable ->
                // Only controls whether to use the stereo *camera config* next session — does not
                // disable the MURAL scan mode (which works on mono).
                forcedStereoUnstable = unstable
            }
        }
        viewModelScope.launch {
            settingsRepository.stereoCapability.collect { cap ->
                // "Capable" now means the probe proved the dual lenses actually triangulate depth (not
                // merely that a stereo config exists and tracks). Only decides whether to adopt the
                // stereo camera config; the MURAL scan mode stays available on mono either way.
                stereoCapable = when (cap) {
                    1 -> true
                    0 -> false
                    else -> null
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.isRightHanded.collect { isRight ->
                _uiState.update { it.copy(isRightHanded = isRight) }
            }
        }
        // The anchor-boundary toggle was removed from Settings; a stored "on" from before is ignored,
        // so the boundary stays off (showAnchorBoundary keeps its false default).
        viewModelScope.launch {
            settingsRepository.isImperialUnits.collect { imperial ->
                _uiState.update { it.copy(isImperialUnits = imperial) }
            }
        }
        viewModelScope.launch {
            settingsRepository.coverageGlowColor.collect { argb ->
                _uiState.update { it.copy(coverageGlow = it.coverageGlow.copy(colorArgb = argb)) }
            }
        }
        viewModelScope.launch {
            settingsRepository.coverageGlowIntensity.collect { intensity ->
                // Skip while a slider drag's debounced write is pending: an older persisted value
                // would otherwise snap the live glow (and the slider) back mid-drag.
                if (coverageGlowIntensityPersistJob?.isActive == true) return@collect
                _uiState.update { it.copy(coverageGlow = it.coverageGlow.copy(intensity = intensity)) }
            }
        }
        viewModelScope.launch {
            settingsRepository.coverageGlowStyle.collect { style ->
                _uiState.update { it.copy(coverageGlow = it.coverageGlow.copy(style = style)) }
            }
        }
        viewModelScope.launch {
            settingsRepository.doodleFeatures.collect { features ->
                _uiState.update { it.copy(doodleFeatures = features) }
            }
        }
        viewModelScope.launch {
            settingsRepository.anticipationSettings.collect { settings ->
                // A slider drag's debounced write pending: the live value is newer than the stored one.
                if (anticipationPersistJob?.isActive == true) return@collect
                _uiState.update { it.copy(anticipation = settings) }
            }
        }
    }

    private var anticipationPersistJob: kotlinx.coroutines.Job? = null

    /** Settings → Doodle. Shown at once, persisted; the next target capture builds with it. */
    fun setDoodleFeatures(features: com.hereliesaz.graffitixr.common.model.DoodleFeatures) {
        _uiState.update { it.copy(doodleFeatures = features) }
        viewModelScope.launch { settingsRepository.setDoodleFeatures(features) }
    }

    /**
     * Settings → Anticipation. Called continuously while a slider is dragged: every value reaches the
     * trackers at once (they read it per frame), while only the settled value is written to DataStore.
     */
    fun setAnticipationSettings(settings: com.hereliesaz.graffitixr.common.model.AnticipationSettings) {
        val sanitized = settings.sanitized()
        _uiState.update { it.copy(anticipation = sanitized) }
        anticipationPersistJob?.cancel()
        anticipationPersistJob = viewModelScope.launch {
            kotlinx.coroutines.delay(COVERAGE_GLOW_PERSIST_DEBOUNCE_MS)
            settingsRepository.setAnticipationSettings(sanitized)
        }
    }

    // Debounces slider drags into one DataStore write; the live value is published immediately.
    private var coverageGlowIntensityPersistJob: kotlinx.coroutines.Job? = null

    /** Coverage-glow colour (opaque ARGB). Applied immediately to the live glow, and persisted. */
    fun setCoverageGlowColor(argb: Int) {
        _uiState.update { it.copy(coverageGlow = it.coverageGlow.copy(colorArgb = argb or OPAQUE_ALPHA)) }
        viewModelScope.launch { settingsRepository.setCoverageGlowColor(argb) }
    }

    /**
     * Coverage-glow intensity. Called continuously while the Settings slider is dragged: every value
     * reaches the live glow at once, while only the settled value is written to DataStore.
     */
    fun setCoverageGlowIntensity(intensity: Float) {
        val sanitized = com.hereliesaz.graffitixr.common.model.CoverageGlowIntensity.sanitize(intensity)
        _uiState.update { it.copy(coverageGlow = it.coverageGlow.copy(intensity = sanitized)) }
        coverageGlowIntensityPersistJob?.cancel()
        coverageGlowIntensityPersistJob = viewModelScope.launch {
            kotlinx.coroutines.delay(COVERAGE_GLOW_PERSIST_DEBOUNCE_MS)
            settingsRepository.setCoverageGlowIntensity(sanitized)
        }
    }

    /** Coverage-glow style. Applied immediately to the live glow, and persisted. */
    fun setCoverageGlowStyle(style: com.hereliesaz.graffitixr.common.model.CoverageGlowStyle) {
        _uiState.update { it.copy(coverageGlow = it.coverageGlow.copy(style = style)) }
        viewModelScope.launch { settingsRepository.setCoverageGlowStyle(style) }
    }

    /**
     * Restores the current project's ARCore-frame state into native — the gated half of the
     * currentProject collector, and the reverse half of a live "Use ARCore" switch back on (standalone
     * had its own page-frame fingerprint installed in the process-global MobileGS).
     */
    private fun restoreLocalArCoreProjectState(
        project: com.hereliesaz.graffitixr.common.model.GraffitiProject,
    ) {
        if (
            !shouldRestoreLocalArCoreDoodle(
                isArCoreAvailable = _uiState.value.isArCoreAvailable,
                coopRole = _uiState.value.coopRole,
                peerSpatialFramePresent = _uiState.value.coopPeerSpatialFrame != null,
            )
        ) {
            return
        }
        loadMapIfExists()
        loadDoodleIfExists()
        // A page frozen this session commits once its own doodle lands.
        tryCommitPendingHybridPage(project)
        // Once per project page, not per emission: re-arming KPM resets the sidecar.
        // Keyed by URI too, so a page committed after a renderer swap is still offered.
        if (hybridRestoreKey(project) != hybridRestoreKey) {
            hybridRestoreKey = hybridRestoreKey(project)
            pushPersistedHybridPage(project)
        }
    }

    // arCoreCheckResult is declared above the init block that sets it; see the note there.

    /**
     * Selects the AR backend from the cached checker [result] and the "Use ARCore" setting. Runs at
     * startup (first resolution) and again on every toggle, so the switch is immediate — including in
     * the middle of an AR session:
     *
     * - **ARCore → standalone.** The ARCore session is torn down exactly as leaving AR does, and the
     *   teardown is AWAITED before the state flips, so ARCore has released the camera before MainScreen
     *   binds CameraX for the standalone path (the two racing for the device is the crash described in
     *   MainScreen's AR-entry effect). Its ARCore-frame wall map is persisted while saves still route to
     *   the ARCore branch, then the native ARCore frame is cleared — the same cleanup the startup path
     *   does — so the standalone analyzer installs its page-frame fingerprint deterministically.
     * - **standalone → ARCore.** Publishing the state is enough for MainScreen: the standalone branch
     *   leaves composition (its analyzer stops, CameraX unbinds) and the ARCore branch's AR-entry
     *   effect opens the Session behind the camera-availability gate. The project's ARCore-frame
     *   doodle is restored here the way the currentProject collector does.
     *
     * Either way a co-op session ends (its spatial frame belongs to the old backend), and SphereSLAM
     * runtime selection and monocular depth are re-evaluated.
     */
    private suspend fun applyArCoreBackendSelection(
        result: ArAvailabilityChecker.Result,
        arCoreOptIn: Boolean,
    ) {
        val supported = isArCoreBackendSelected(result, arCoreOptIn)
        val before = _uiState.value
        val firstResolution = !before.isArCoreAvailabilityResolved
        if (!firstResolution && before.isArCoreAvailable == supported) return
        if (!supported) {
            if (!firstResolution) stopArCoreForStandaloneSwitch()
            // The native MobileGS instance is process-global. Project loading may have installed
            // an ARCore/capture-camera doodle while ARCore capability was still UNKNOWN (or, on a
            // live switch, while ARCore was the backend). Clear that frame BEFORE publishing "ARCore
            // unavailable"; the standalone analyzer starts only after this state update and will then
            // install the centered page-frame fingerprint deterministically.
            loadedDoodle = null
            nativeStateOwner.clear()
            slamManager.clearWallDoodle()
            slamManager.clearWallFeatureMap()
            slamManager.overlayMarkCenterLocal = null
            slamManager.captureAnchorCam = null
        }
        _uiState.update {
            it.copy(
                isArCoreAvailable = supported,
                isArCoreAvailabilityResolved = true,
            )
        }
        Timber.i("ArCore backend selected: result=$result optIn=$arCoreOptIn (supported=$supported)")
        if (!firstResolution) {
            appendDiag(
                "Use ARCore ${if (arCoreOptIn) "on" else "off"} — tracking backend now " +
                    if (supported) "ARCore" else "standalone SphereSLAM",
            )
            if (_uiState.value.coopRole != com.hereliesaz.graffitixr.common.model.CoopRole.NONE) {
                _feedback.tryEmit(
                    com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(
                        appContext.getString(DesignR.string.ar_feedback_backend_changed)
                    )
                )
                leaveSession()
            }
            if (supported) {
                loadedDoodle = null
                projectRepository.currentProject.value?.let { restoreLocalArCoreProjectState(it) }
            }
        }
        refreshSphereSlamRuntimeSelection()
        reconcileMonocularDepth()
    }

    /**
     * The ARCore half of a live switch to standalone: the same teardown as leaving AR ([exitArMode]),
     * awaited, then this session's ARCore-frame wall map/paint saved while the state still says ARCore
     * (saveProjectWallMap routes on it). MainScreen calls exitArMode again when the ARCore branch leaves
     * composition; on an already-closed session that is a no-op teardown.
     */
    private suspend fun stopArCoreForStandaloneSwitch() {
        if (isInArMode || session != null) {
            exitArMode().join()
            try {
                withContext(dispatchers.io) { saveProjectWallMap() }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.e(e, "ARCore wall map save before backend switch failed")
            }
        }
        // Wait out any teardown still in flight (e.g. a just-exited AR session) before its native
        // frame is cleared.
        sessionMutex.withLock { }
    }

    // monocularDepthMutex is declared above the init block; see the note there.

    /**
     * Loads + self-tests, or unloads, the monocular depth model to match its settings, immediately.
     * Wanted only when the setting is on AND the standalone (non-ARCore) backend is selected: the
     * standalone sphere map is its only consumer. The ARCore path has none — MiDaS depth is relative
     * (affine-ambiguous), so it cannot stand in for ARCore's metric Depth API in hit-tests or plane
     * finding — so an ARCore device, with its depth on or off, never loads it. The self-test runs one inference on a
     * synthetic image to confirm the LiteRT model (SphereSLAM :models) LOADS and EXECUTES on this hardware; the
     * result goes to the diag overlay. Turning it off releases the model unless the feature-map
     * analyzer is using it. Off the main thread; no-op until ARCore availability has resolved.
     */
    private fun reconcileMonocularDepth() {
        val result = arCoreCheckResult ?: return
        viewModelScope.launch(dispatchers.io) {
            monocularDepthMutex.withLock {
                val wanted = isMonocularDepthWanted(
                    monocularDepthOptIn = settingsRepository.monocularDepthEnabled.firstOrNull() != false,
                    arCoreBackendSelected = isArCoreBackendSelected(
                        result,
                        arCoreOptIn = settingsRepository.arCoreEnabled.firstOrNull() ?: true,
                    ),
                )
                val loaded = depthEstimatorLazy.isInitialized() && depthEstimator.isLoaded
                if (wanted && !loaded) {
                    runCatching { selfTestDepth() }
                        .onFailure { Timber.w(it, "depth self-test threw") }
                } else if (!wanted && loaded && !_evalFeatureMapEnabled.value) {
                    depthEstimator.close()
                    // `close()` releases the model now, but this estimator is shared by reference with a
                    // standalone analyzer that may already hold it (see standaloneDepthEstimatorOrNull);
                    // that analyzer loads on demand, so "released" is what is true — not "unloaded for
                    // good". A session that was started without depth never receives the estimator.
                    appendDiag("depth: monocular depth not needed — model released (reloads on demand if a running analyzer still asks)")
                }
            }
        }
    }

    // Sticky: a device proved its forced hardware-stereo path is broken (ARCore motion-stereo
    // disparity fails / VIO never tracks). Persisted; once set, sessions skip the stereo config.
    @Volatile private var forcedStereoUnstable: Boolean = false

    // Set when the dead-camera watchdog reports the HAL never streamed a frame (e.g. Galaxy A26
    // budget HAL). Makes the NEXT AR entry (same process) skip the fps-variant swap and run ARCore's
    // pure default camera config — the most broadly compatible. Process-scoped (NOT persisted): if
    // the safe config also fails the cause is below the config layer, and a fresh launch should retry
    // the normal path once (the watchdog re-catches it) rather than be permanently pinned to a config
    // that may not work either.
    @Volatile private var forceSafeCameraConfig: Boolean = false

    // One-time dual-lens probe result: null = not yet probed (or the last probe could not open the
    // camera), true = the forced stereo config is in force, tracks and yields depth (adopt it),
    // false = no usable depth / thrashes / never tracks (stay mono). Persisted via SettingsRepository.stereoCapability so it runs once per install.
    @Volatile private var stereoCapable: Boolean? = null
    private val stereoProbeInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    // The in-flight session-state update (probe + init/resume/pause). Cancelled on the next update so
    // a running probe stops and releases the camera immediately when the activity pauses or AR exits.
    private var sessionUpdateJob: kotlinx.coroutines.Job? = null

    // MURAL (gaussian-splat SLAM) and the other mural methods run on a mono camera using VIO /
    // motion depth — they do NOT require hardware stereo. The mono-vs-stereo *camera config* is
    // chosen separately (probe + initArSessionLocked); it must not disable the MURAL *scan mode*.
    // So the scan mode is never auto-downgraded to Canvas; the user picks Canvas explicitly if wanted.

    /** The Settings switch for pass B: persisted, and handed to the native engine by the collector. */
    fun setChangePassEnabled(on: Boolean) {
        viewModelScope.launch { settingsRepository.setChangePassEnabled(on) }
    }

    fun setAmbientScanEnabled(enabled: Boolean) {
        // Turning the sweep back ON is a retry: clear the sticky stereo-unstable flag so the next
        // session re-evaluates hardware stereo (recovering from a false positive). This used to hang
        // off "the user explicitly chose Mural", which was the same gesture under the old two-mode
        // naming — the sweep is what Mural actually meant.
        if (enabled && forcedStereoUnstable) {
            forcedStereoUnstable = false
            viewModelScope.launch { settingsRepository.setForcedStereoUnstable(false) }
        }
        // Persist so it survives restarts (the flow re-emits it; the direct update below just makes
        // the UI reflect it immediately).
        viewModelScope.launch { settingsRepository.setAmbientScanEnabled(enabled) }
        applyAmbientScanEnabled(enabled)
    }

    /**
     * Publishes the ambient-sweep setting and, when it turns ON, restarts the scan progression.
     * scanPhase latches COMPLETE and the yaw sectors only ever fill, so without the reset a sweep
     * switched back on after a scan (or after any earlier session) never ran again.
     */
    private fun applyAmbientScanEnabled(enabled: Boolean) {
        val wasEnabled = _uiState.value.ambientScanEnabled
        _uiState.update { it.copy(ambientScanEnabled = enabled) }
        if (enabled && !wasEnabled) resetScanProgress()
    }

    /**
     * Back to the start of the scan progression: no yaw sectors visited, phase AMBIENT (which falls
     * straight through to WALL on the next tracking tick when the sweep is off). Called on AR exit, on
     * a project switch, and when the ambient sweep is switched on.
     */
    private fun resetScanProgress() {
        synchronized(visitedSectors) { java.util.Arrays.fill(visitedSectors, false) }
        _uiState.update {
            it.copy(
                scanPhase = ScanPhase.AMBIENT,
                ambientSectorsCovered = 0,
                worldMappingProgress = 0f,
                visitedSectorsMask = 0L,
            )
        }
    }


    /** Applied to the live renderer at once and persisted; restored on launch by its collector. */
    fun setShowAnchorBoundary(show: Boolean) {
        _uiState.update { it.copy(showAnchorBoundary = show) }
        renderer?.showAnchorBoundary = show
        viewModelScope.launch { settingsRepository.setShowAnchorBoundary(show) }
    }

    /**
     * Persist the camera target fps. Applied immediately: a live ARCore session's camera config is
     * re-selected by the cameraTargetFps collector (reconcileLiveSessionSettings).
     */
    fun setCameraTargetFps(fps: Int) {
        viewModelScope.launch { settingsRepository.setCameraTargetFps(fps) }
    }

    fun setArCoreEnabled(on: Boolean) {
        viewModelScope.launch { settingsRepository.setArCoreEnabled(on) }
    }

    fun setArCoreDepthEnabled(on: Boolean) {
        viewModelScope.launch { settingsRepository.setArCoreDepthEnabled(on) }
    }

    fun setMonocularDepthEnabled(on: Boolean) {
        viewModelScope.launch { settingsRepository.setMonocularDepthEnabled(on) }
    }

    /** Settings ▸ Power saving: arms or disarms all four throttle triggers together. */
    fun setPowerSaving(on: Boolean) {
        viewModelScope.launch {
            settingsRepository.setThrottleOnThermal(on)
            settingsRepository.setThrottleOnPowerSave(on)
            settingsRepository.setThrottleOnLowBattery(on)
            settingsRepository.setThrottleOnLag(on)
        }
    }

    fun setAdaptiveRateEnabled(on: Boolean) {
        viewModelScope.launch { settingsRepository.setAdaptiveRateEnabled(on) }
    }

    fun setImperialUnits(imperial: Boolean) {
        // Optimistic state update for a snappy toggle, plus persist so the choice survives a restart
        // (the isImperialUnits collector re-applies the stored value). Previously state-only → lost.
        _uiState.update { it.copy(isImperialUnits = imperial) }
        viewModelScope.launch { settingsRepository.setImperialUnits(imperial) }
    }

    fun onActivityResumed() {
        isActivityResumed = true
        updateSessionStateLocked()
    }

    fun onActivityPaused() {
        isActivityResumed = false
        updateSessionStateLocked()
    }

    /**
     * Screen-off, home, another app: save now. Autosave runs every 30 s and only past its deltas, and
     * a process killed in the background never reaches the teardown save, so without this whatever
     * was learned since the last autosave (paint, grown Doodle rows, map points) was lost.
     */
    fun onActivityStopped() {
        if (loadedProjectId != null) saveMapNow()
    }

    /**
     * Called by the dead-camera watchdog when the camera HAL never delivered a frame to ARCore.
     * Pins the safest ARCore default camera config for the next AR entry this process (no fps-variant
     * swap, no hardware stereo). Idempotent; process-scoped so a fresh launch retries normally.
     */
    fun onCameraStreamStalled() {
        forceSafeCameraConfig = true
        Timber.w("ARDIAG onCameraStreamStalled: next AR entry will use ARCore default camera config (safe mode)")
    }

    /**
     * Physical device attitude. Created lazily so a device with no motion sensors costs nothing, and
     * started/stopped with AR mode — sensors left registered drain a phone the artist is holding up
     * for an hour.
     */
    private var attitudeProvider: DeviceAttitudeProvider? = null

    /** The fix taken for the latest capture. Null until one arrives, or if denied. */
    @Volatile
    private var lastLocation: android.location.Location? = null
    private var locationTracker: com.hereliesaz.graffitixr.common.LocationTracker? = null

    /**
     * Begin sampling attitude and location for capture records. Idempotent.
     *
     * Location is one fix taken when a capture begins ([requestCaptureLocationFix]) and cached: the
     * capture happens on the GL thread inside a frame callback and cannot wait on a fix. A cached
     * value with its age recorded is strictly better than a blocking call that would drop frames —
     * and the age is what makes a stale fix visible instead of silently wrong.
     */
    private fun startEnvironmentSampling(context: Context) {
        // Each source is guarded separately, and neither can stop AR mode.
        //
        // This is a correctness requirement, not defensiveness for its own sake: the capture record
        // is OPTIONAL telemetry, and AR is the product. A device with no SENSOR_SERVICE, or a
        // Play-Services location client that refuses to start (it needs a Looper, and throws
        // "invalid null looper" without one), must degrade to recording less — never to an artist
        // unable to enter AR. The first version of this let a location-client failure propagate out
        // of setArMode and take the session with it.
        try {
            if (attitudeProvider == null) {
                attitudeProvider = DeviceAttitudeProvider(context).also {
                    if (!it.isAvailable) Timber.i("No motion sensors; captures will record no attitude")
                }
            }
            attitudeProvider?.start()
        } catch (t: Throwable) {
            Timber.w(t, "attitude sampling unavailable; captures will record no attitude")
            attitudeProvider = null
        }

        try {
            if (locationTracker == null) {
                locationTracker = com.hereliesaz.graffitixr.common.LocationTracker(context)
            }
            // No subscription: one fix is taken when a capture begins (requestCaptureLocationFix),
            // not GPS held hot for the whole session.
        } catch (t: Throwable) {
            Timber.w(t, "location sampling unavailable; captures will record no location")
            locationTracker = null
        }
    }

    /**
     * Stop sampling. Must run whenever AR stops, or the sensors and GPS stay hot.
     *
     * Also guarded: a teardown that throws would leave the other source registered, which is the
     * exact leak this method exists to prevent.
     */
    private fun stopEnvironmentSampling() {
        try {
            attitudeProvider?.stop()
        } catch (t: Throwable) {
            Timber.w(t, "attitude sampling teardown failed")
        }
        try {
            locationTracker?.stopLocationUpdates()
        } catch (t: Throwable) {
            Timber.w(t, "location sampling teardown failed")
        }
    }

    /**
     * A target capture is starting: take one balanced-accuracy fix for its record. The capture itself
     * runs on the GL thread and cannot wait, so the fix lands in [lastLocation] while the artist taps
     * the marks. Without permission it waits for the grant (asked for at this same moment).
     */
    fun requestCaptureLocationFix() {
        try {
            locationTracker?.requestSingleFix { loc -> lastLocation = loc }
        } catch (t: Throwable) {
            Timber.w(t, "location fix unavailable; the capture records no location")
        }
    }

    /** The latest location as a serializable fix, with its age. Null when none has arrived. */
    private fun locationFix(): com.hereliesaz.graffitixr.common.model.LocationFix? {
        val l = lastLocation ?: return null
        val ageMs = if (l.elapsedRealtimeNanos > 0L) {
            (android.os.SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000L
        } else {
            -1L
        }
        return com.hereliesaz.graffitixr.common.model.LocationFix(
            latitude = l.latitude,
            longitude = l.longitude,
            altitudeM = if (l.hasAltitude()) l.altitude else Double.NaN,
            horizontalAccuracyM = if (l.hasAccuracy()) l.accuracy else -1f,
            verticalAccuracyM = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                l.hasVerticalAccuracy()
            ) l.verticalAccuracyMeters else -1f,
            bearingDeg = if (l.hasBearing()) l.bearing else Float.NaN,
            speedMps = if (l.hasSpeed()) l.speed else Float.NaN,
            provider = l.provider ?: "",
            ageMs = ageMs,
        )
    }

    /**
     * Enter/leave the **ARCore-backed** runtime.
     *
     * MainScreen calls this only in the ARCore-capable branch. AR mode itself is not ARCore-only:
     * unsupported devices keep this ViewModel's ARCore session state false and run CameraX +
     * SphereSLAM directly. The availability guard therefore protects Session construction; it does
     * not mean the product's AR mode is unavailable.
     */
    fun setArMode(enabled: Boolean, context: Context) {
        val capability = _uiState.value
        if (
            enabled &&
            (!capability.isArCoreAvailabilityResolved || !capability.isArCoreAvailable)
        ) {
            Timber.w(
                "setArMode(true) ignored: ARCore capability is unresolved/unavailable; " +
                    "CameraX + SphereSLAM owns the safe path",
            )
            return
        }
        isInArMode = enabled
        // A new/destroyed session resets native mapping to running, so drop our cached command and
        // let the next tracking frame re-assert the correct auto-mapping state.
        lastMappingPausedCmd = null
        Timber.i(
            "ARDIAG setArMode(enabled=$enabled) " +
                "layers=${projectRepository.currentProject.value?.layers?.size ?: 0} " +
                "ambientScan=${_uiState.value.ambientScanEnabled} sessionExists=${session != null}"
        )
        if (enabled) {
            // Standalone SphereSLAM uses the same native MobileGS instance but installs a page-frame
            // doodle into it. Force the normal ARCore doodle loader to touch native again
            // on ARCore entry even when the project id/value itself did not change.
            loadedDoodle = null
            val peerFrame = _uiState.value.coopPeerSpatialFrame
            val peerDoodle = arCorePeerDoodle
            if (
                shouldRestoreLocalArCoreDoodle(
                    isArCoreAvailable = _uiState.value.isArCoreAvailable,
                    coopRole = _uiState.value.coopRole,
                    peerSpatialFramePresent = peerFrame != null,
                )
            ) {
                loadDoodleIfExists()
            } else if (peerFrame != null && peerDoodle != null) {
                // A guest: the local copy of the host project carries host intrinsics, anchor and
                // view. Re-install the host's geometry instead (standalone may have replaced it).
                viewModelScope.launch(Dispatchers.IO) { installArCorePeer(peerDoodle, peerFrame) }
            }

            val now = System.currentTimeMillis()
            arEntryTimestampMs = now
            lastTrackingTimestampMs = now
            trackedSinceArEntry = false
            // A fresh AR session gets a fresh watcher. This view model outlives the session — AR is
            // a mode switch inside it — so without this the previous session's edge state, latches
            // and spent budget carry over: the first tick of session two compares against session
            // one's last state and photographs a "lost lock" from a different wall, while every
            // once-only trigger stays latched for the life of the process and can never fire again.
            diagnosticWatcher.reset()
            if (_uiState.value.trackingFailed) {
                _uiState.update { it.copy(trackingFailed = false) }
            }
            startEnvironmentSampling(context)
        } else {
            stopEnvironmentSampling()
        }
        updateSessionStateLocked(context)
    }

    /**
     * Hard-exit AR mode: flips the in-AR / destroying flags synchronously so
     * the GL render loop stops doing work on the next tick, then schedules a
     * full session close on a background dispatcher. Safe to call from the UI
     * thread — does not block on the session mutex. Use this instead of
     * setArMode(false) when the user is leaving AR (back press, mode switch,
     * tracking-failed overlay), because a paused-but-not-closed session leaves
     * the camera attached on some devices and produces the "AppOps Operation
     * not started: op=CAMERA" symptom on the next entry.
     */
    fun exitArMode(): Job {
        isInArMode = false
        isDestroying = true
        _uiState.value.sphereSlamRuntimeStatus
            .takeIf {
                it.mode ==
                    com.hereliesaz.graffitixr.common.model.SphereSlamRuntimeMode.ARCORE_SIDECAR
            }
            ?.let {
                publishSphereSlamRuntimeStatus(
                    it.copy(active = false, referenceReady = false, trackingData = false)
                )
            }
        // Release the compass sensor; re-armed when the standalone AR composition next resumes.
        cameraAttitudeProvider.stop()
        // Stop the GL thread FIRST, before any of the state below is cleared.
        //
        // `onDrawFrame` returns immediately on this flag, and until it is set the renderer keeps
        // running full frames — `anchorEstablished` is a one-way latch, so `placed` stays true right
        // through teardown. `clearAnchorEstablished()` two lines down advances the anchor
        // generation, which the move detector reads as a change, so the renderer would publish a
        // footprint and `onDesignFootprintChanged` would write `latestDesignFootprint` straight back
        // after the line below nulls it. The next session would then partition its doodle
        // against the DEAD session's design pose. Ordering is the whole fix.
        renderer?.isDestroying = true
        lastMappingPausedCmd = null
        resetSessionAnchorState()
        // Anchor gone — clear any manual override and return the live mode to the artist's saved
        // preference, which re-arms acquire-then-lock for an AUTO preference. The preference itself
        // (UI state and DataStore) is untouched.
        focusManuallyOverridden = false
        anchorIsClose = false
        effectiveAutoFocus = _evalAutoFocusEnabled.value
        // Cancel any in-flight session update (including a running stereo probe) so it stops pumping
        // the camera and releases the session mutex before cleanup tries to acquire it.
        sessionUpdateJob?.cancel()
        if (_uiState.value.trackingFailed) {
            _uiState.update { it.copy(trackingFailed = false) }
        }
        // teardownScope (default dispatcher), NOT viewModelScope's Main: performFullCleanupLocked
        // awaits saveMapBlocking, then session.pause() and session.close() — and close() BLOCKS
        // until any in-flight session.update() returns. When the GL thread is wedged inside
        // update() (camera never fed ARCore), running this on Main froze the entire app on AR
        // exit — exactly when the diag tells the user to exit. teardownScope also survives
        // ViewModel clearing, so an exit racing onCleared still closes the session.
        //
        // The renderer is captured NOW, on the caller's thread: by the time the teardown holds the
        // session mutex, MainScreen may already have attached the NEXT AR entry's renderer, which this
        // teardown must neither detach nor null (see performFullCleanupLocked).
        val ownedRenderer = renderer
        return teardownScope.launch {
            performFullCleanupLocked(ownedRenderer)
        }
    }

    private fun updateSessionStateLocked(callerContext: Context? = null) {
        // Lifecycle callers (onActivityResumed/onActivityPaused) have no Context to hand. A pause/resume
        // that lands while AR entry is still probing or waiting on the camera gate cancels the only job
        // that was going to create the Session, so the resume must be able to create it itself — fall
        // back to the application Context rather than skipping creation (ARCore's Session accepts any
        // Context; the probe already binds through the application context).
        val context: Context = callerContext ?: appContext
        sessionUpdateJob?.cancel()
        // Dispatchers.Default, NOT the main thread: initArSessionLocked creates/configures the ARCore
        // Session and resumeArSessionInternal calls session.resume() — which OPENS THE CAMERA and can
        // block for hundreds of ms (or hang on a wedged camera). Running that on the main thread froze
        // the whole UI (ANR). Off-main, a slow/blocked camera open leaves the UI responsive so the user
        // can back out. All session ops stay serialized by sessionMutex; _uiState and renderer access
        // are thread-safe.
        sessionUpdateJob = viewModelScope.launch(dispatchers.default) {
            sessionMutex.withLock {
                val capability = _uiState.value
                val arCoreReady =
                    capability.isArCoreAvailabilityResolved && capability.isArCoreAvailable
                // Only while the activity is in the foreground: a backgrounded app cannot open the
                // camera, and a probe run then would only lose that race. onActivityResumed re-runs
                // this (with the application context) and creates the session.
                if (
                    isActivityResumed &&
                    isInArMode &&
                    session == null &&
                    !isDestroying &&
                    arCoreReady
                ) {
                    // Wait for the camera to actually be FREE before ARCore opens it. AR entry calls
                    // cameraController.unbind() (CameraX) whose camera-device close is ASYNCHRONOUS, so
                    // opening the ARCore session immediately races CameraX's release: ARCore gets the
                    // device but no frames flow (ts=0, update() hangs — the intermittent black camera).
                    // Registering the availability callback delivers current state immediately, so this
                    // is ~free when the camera is already idle and only waits out the CameraX (or a
                    // crashed :probe) release. This is the gate that makes AR entry deterministic.
                    // The gate's verdict is now LOAD-BEARING: a session opened on a camera the gate
                    // couldn't confirm free is doomed to ts=0 frames and a wedged update(), so on
                    // timeout/failure we refuse to open the session and tell the user instead.
                    //
                    // First-ever AR entry on an unprobed device: once the gate has confirmed the camera
                    // is free (no live session yet), run a short throwaway stereo session in an
                    // isolated process (see StereoProbeService for what its verdict proves). AFTER the
                    // gate, never before: probing a camera CameraX is still releasing loses the race
                    // and used to be persisted as a permanent mono verdict. Inside the session mutex
                    // so no concurrent update can open the live session while the probe holds the
                    // camera; capped and off the main thread so a thrashing device can't ANR.
                    // Cancelling this job (AR exit / pause) cancels the probe too. The probe process
                    // releases the camera asynchronously, so the gate runs again before the session.
                    var cameraFree = awaitCameraAvailable(context, 3000L)
                    if (cameraFree && stereoCapable == null && probeStereoCapability(context)) {
                        cameraFree = awaitCameraAvailable(context, 3000L)
                    }
                    if (cameraFree) {
                        initArSessionLocked(context)
                    } else {
                        appendDiag("camera gate FAILED — refusing to open AR session on an unconfirmed camera")
                        _feedback.tryEmit(
                            com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(
                                appContext.getString(DesignR.string.ar_feedback_camera_busy)
                            )
                        )
                    }
                }

                if (isActivityResumed && isInArMode && !isSessionResumed && !isDestroying && session != null) {
                    // Time the camera open: if this is slow it explains a frozen-feeling AR entry.
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    resumeArSessionInternal()
                    appendDiag("session.resume() took ${android.os.SystemClock.elapsedRealtime() - t0}ms")
                } else if ((!isActivityResumed || !isInArMode) && isSessionResumed) {
                    pauseArSessionInternal()
                }
            }
        }
    }

    /**
     * Suspend until the back-facing camera is reported available by the camera service, or [timeoutMs]
     * elapses. Registering an availability callback immediately delivers the current state, so this
     * returns instantly when the camera is already free; it only actually waits when something (e.g. a
     * just-crashed :probe process, or the wedged previous session of a force-killed process) still
     * holds the camera. Never throws.
     *
     * Returns true only when the camera was confirmed available. Returns false on timeout AND on any
     * exception (a wedged cameraserver makes cameraIdList itself throw) — both previously fell through
     * silently (logcat-only) and the session was opened anyway on a busy camera, producing the ts=0 /
     * wedged-update() black screen with no "camera handoff" line in the on-screen diag. Every exit
     * path now appends a diag line so the gate can never silently no-op again. The only non-confirmed
     * true is when no back camera can even be enumerated structurally (null manager / no back id):
     * there is nothing to wait on, and refusing would permanently lock such a device out of AR.
     */
    private suspend fun awaitCameraAvailable(context: Context, timeoutMs: Long): Boolean {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE)
                as? android.hardware.camera2.CameraManager
            if (cm == null) {
                appendDiag("camera gate: no CameraManager — skipping wait")
                return true
            }
            val backId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            }
            if (backId == null) {
                appendDiag("camera gate: no back camera enumerated — skipping wait")
                return true
            }
            val available = kotlinx.coroutines.CompletableDeferred<Unit>()
            val cb = object : android.hardware.camera2.CameraManager.AvailabilityCallback() {
                override fun onCameraAvailable(cameraId: String) {
                    if (cameraId == backId && !available.isCompleted) available.complete(Unit)
                }
            }
            cm.registerAvailabilityCallback(cb, Handler(Looper.getMainLooper()))
            return try {
                val freed = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { available.await() } != null
                if (freed) {
                    // The HAL reports the camera device closed, but CameraX's internal capture-session
                    // teardown (running on its own executor) may still have in-flight requests draining.
                    // Opening ARCore's Session immediately races those stragglers: CameraX fires
                    // ERROR_CAMERA_DEVICE on its dying pipeline, and an uncaught SecurityException from
                    // getCameraCharacteristics on an ARCore worker thread hard-crashes the process. A
                    // short settling delay lets CameraX's executor drain before ARCore takes the device.
                    kotlinx.coroutines.delay(300)
                }
                appendDiag("camera handoff: ${if (freed) "available" else "TIMEOUT after ${timeoutMs}ms"}")
                freed
            } finally {
                cm.unregisterAvailabilityCallback(cb)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A cancelled entry (activity pause, AR exit, a newer state update) is not a camera fault:
            // no "camera busy" toast, no diag line blaming the camera service.
            throw e
        } catch (e: Exception) {
            Timber.w(e, "ARDIAG awaitCameraAvailable failed")
            appendDiag("camera gate: threw ${e.javaClass.simpleName} — camera service unhealthy")
            return false
        }
    }

    private fun initArSessionLocked(context: Context) {
        if (session != null || isDestroying) return
        Timber.i("ARDIAG initArSessionLocked: creating session")
        try {
            val s = Session(context)
            val config = Config(s)
            // AUTO, except in safe mode. FIXED parks the lens at infinity, which is right for a
            // room-scale tracking app but wrong for this one: an artist works a wall at arm's length,
            // often at night with a torch, and at that range a fixed-infinity lens delivers a blurred
            // frame with no usable texture. ARCore then finds no features, so no plane ever forms —
            // "impossible to get a surface to read in low light, even with a flashlight" — and the
            // fragments it does form are too noisy to merge into one wall. The blur cost was called
            // out when FIXED was chosen ("Revisit if nearby surfaces render too blurred for mark
            // detection"); close-range low light is exactly that case, so AUTO is the default now.
            //
            // The stability reason for FIXED is preserved: autofocus sweeps shift the effective focal
            // length mid-stream and on devices with sloppy OEM intrinsics that destabilized ARCore's
            // triangulation badly enough to segfault its perception threads (MTC_vio,
            // MTC_triangulati) inside libarcore_c. forceSafeCameraConfig is the flag those devices
            // already set after a camera stall, so safe mode keeps the old constant-optics behaviour.
            // Safe mode still forces FIXED (those devices set the flag precisely because constant
            // optics is what stopped them crashing). Otherwise the artist's switch decides; it
            // defaults to AUTO, which is what this line used to hardcode.
            config.focusMode = when {
                forceSafeCameraConfig -> Config.FocusMode.FIXED
                effectiveAutoFocus -> Config.FocusMode.AUTO
                else -> Config.FocusMode.FIXED
            }
            // LATEST_CAMERA_IMAGE so session.update() never blocks the GL render thread. On this
            // device the BLOCKING mode left the renderer stuck "Waiting for first frame" (the render
            // heartbeat never fired) — update() hung waiting for a frame that never arrived, so the
            // camera passthrough never drew. LATEST returns the most recent frame immediately, letting
            // onDrawFrame tick and draw the camera even when ARCore's perception pipeline is degraded.
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            // Detect walls so the overlay can anchor to the real surface at its true distance. Without
            // this, hit-tests only return sparse feature points and the overlay lands short of the wall.
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            
            // ARCore's Depth API (DepthMode.AUTOMATIC) is monocular/ML depth — built for single-camera
            // phones with no depth sensor — and it is what feeds plane finding and stabilizes the sparse
            // feature cloud. On without it, a textured wall at near-zero parallax yields zero planes and
            // an unconstrained, drifting cloud. It is user-controlled (Settings → "ARCore depth",
            // default on), still gated by isDepthModeSupported. The off switch remains because on some
            // hardware the ML depth graph errored continuously and starved VIO into kNotTracking
            // (logcat: feature_track_ml_depth_provider + mediapipe normal_detector RET_CHECK); disabling
            // it there keeps tracking alive at the cost of plane/depth.
            //
            // Safe mode (forceSafeCameraConfig, armed after a camera stall) ALSO drops depth, not just
            // the fps swap: a session whose camera never streamed a frame is exactly the ML-depth-starves-
            // VIO hardware above, and depth is the one config knob that otherwise survives the safe-config
            // retry — so leaving it on made the retry stall again (ts stuck at 0, PAUSED) with no way out.
            // Dropping it on retry is the automatic form of the per-device "ARCore depth" opt-out.
            // A later toggle of the setting re-applies it to this live session (reconcileLiveSessionSettings).
            val depthRequested = applyArCoreDepthMode(s, config)

            s.configure(config)
            // ARCore's own pick, before any stereo/fps swap below — what DEVICE_DEFAULT returns to when
            // the artist changes the fps setting mid-session.
            val defaultCameraConfig = s.cameraConfig

            // Camera config selection. Forcing a hardware-stereo config makes ARCore run motion-stereo
            // (spherical_rectifier); on devices that list a "stereo" config but can't actually compute
            // disparity that pegs the CPU, starves VIO into kNotTracking, and ANRs the main thread with
            // a black camera. So we only select stereo when the one-time worker-thread probe has proven
            // this device's dual lenses actually triangulate depth (stereoCapable == true) and it hasn't
            // since been marked unstable. Everything else stays on the safe mono 30 FPS back config.
            // Snapshot the @Volatile decision inputs once: this runs on Dispatchers.Default while the
            // settings collectors and the dead-camera watchdog flip these flags on the main thread, so
            // reading each member at multiple points could see them change mid-init. One snapshot keeps
            // the whole session-build decision self-consistent (it's a per-session decision anyway).
            val safeConfigForced = forceSafeCameraConfig
            val stereoUnstable = forcedStereoUnstable
            val isStereoCapable = stereoCapable
            val useStereo = isStereoCapable == true && !stereoUnstable && !safeConfigForced
            var stereoActive = false
            var appliedFps: Int? = null
            if (useStereo) {
                val stereoConfigs = try {
                    s.getSupportedCameraConfigs(CameraConfigFilter(s).apply {
                        facingDirection = CameraConfig.FacingDirection.BACK
                        stereoCameraUsage = EnumSet.of(CameraConfig.StereoCameraUsage.REQUIRE_AND_USE)
                    })
                } catch (e: Exception) {
                    Timber.w(e, "dual-lens: stereo config query failed")
                    emptyList()
                }
                if (stereoConfigs.isNotEmpty()) {
                    s.cameraConfig = stereoConfigs[0]
                    stereoActive = true
                    Timber.i("ARDIAG dual-lens: HARDWARE STEREO selected (probe-confirmed) cameraId=${stereoConfigs[0].cameraId}")
                }
            }
            if (!stereoActive) {
                // Pin the camera frame rate (default 30, user-selectable 30/60) WITHOUT repeating the
                // old wedge: the prior code took getSupportedCameraConfigs(BACK, 30fps)[0] blindly and
                // sometimes picked a config this device opens but never streams from (resume() OK, no
                // frames, update() ANRs). Instead we keep ARCore's DEFAULT config — the broadly
                // compatible one — and only swap to a same-cameraId variant that differs in fps.
                // (Matching cameraId keeps us on the camera/stream ARCore already validated; we don't
                // also match resolution because CameraConfig doesn't expose it in this ARCore version.)
                // If no same-cameraId fps variant exists, we leave the default untouched. After a
                // dead-camera stall we skip the swap entirely (safe mode) — even that minimal swap is
                // suspect on HALs that open a config but never feed it.
                if (safeConfigForced) {
                    // A previous AR entry this process never streamed a frame (dead-camera watchdog).
                    // Don't touch ARCore's default config at all — not even the same-cameraId fps swap,
                    // which is the prime suspect for a config the HAL opens but never feeds. The pure
                    // default is the broadest-compatible config ARCore offers.
                    appendDiag("camera: SAFE MODE — ARCore default config (stream stalled previously, no fps swap)")
                    Timber.w("ARDIAG mono config: SAFE MODE default cam=${s.cameraConfig.cameraId} (forceSafeCameraConfig)")
                } else {
                    // Pin the camera frame rate. Four choices, two of them fixed and two resolved
                    // from the device (CameraTargetFps): 30, 60, DEVICE_DEFAULT and DEVICE_MAX.
                    // Under battery pressure (tier ≥ medium) cap to 30 fps whatever was chosen —
                    // including MAX, which is the one most likely to cook the phone. The battery cap
                    // is decided here at session build only (a mid-session CameraConfig swap resets
                    // tracking); an artist's own change to the fps or adaptive-rate setting is applied
                    // to the live session at once by reconcileLiveSessionSettings.
                    val chosenFps = effectiveCameraTargetFps(
                        targetFps = _uiState.value.cameraTargetFps,
                        adaptiveRateEnabled = _uiState.value.adaptiveRateEnabled,
                        batteryTier = _uiState.value.batteryTier,
                    )
                    applyCameraTargetFps(s, defaultCameraConfig, chosenFps, restoreDefaultWhenUnmatched = false)
                    appliedFps = chosenFps
                    Timber.i("ARDIAG dual-lens: mono config cam=${s.cameraConfig.cameraId} fps=${_uiState.value.cameraTargetFps} (stereoCapable=$isStereoCapable)")
                }
            }

            // isHardwareStereoActive just reflects the camera config for the diag panel; the MURAL
            // scan mode stays available regardless (it works on mono).
            _uiState.update { it.copy(isHardwareStereoActive = stereoActive) }

            appliedDepthRequested = depthRequested
            appliedCameraFps = appliedFps
            sessionDefaultCameraConfig = defaultCameraConfig
            session = s
            _isCameraInUseByAr.value = true
            // Surface the camera-config decision ON SCREEN (not just logcat) so a black-camera report
            // tells us whether the live session is on stereo or mono — the difference between a
            // forced-stereo fault and a deeper camera-feeding fault.
            appendDiag("AR session: ${if (stereoActive) "STEREO" else "mono"} cam=${s.cameraConfig.cameraId} cap=$isStereoCapable unstable=$stereoUnstable")
            Timber.i("ARDIAG initArSessionLocked: session created OK (stereoActive=$stereoActive rendererAttached=${renderer != null})")

            // Critical: if the renderer is already attached, update it with the new session
            renderer?.attachSession(s)
        } catch (e: Exception) {
            Timber.e(e, "ARDIAG Failed to create ARCore session -> camera black")
            // Surface the failure instead of leaving the user on a frozen black AR screen.
            _isCameraInUseByAr.value = false
            _feedback.tryEmit(
                com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(appContext.getString(DesignR.string.ar_feedback_ar_start_failed), e)
            )
        }
    }

    /**
     * Sets [config]'s depth mode from the "ARCore depth" setting (dropped in camera safe mode — see
     * initArSessionLocked), still gated by the device's isDepthModeSupported for the session's current
     * camera config, and mirrors the result to the UI and the renderer. Returns whether depth was
     * requested. The caller applies [config] with Session.configure.
     */
    private fun applyArCoreDepthMode(s: Session, config: Config): Boolean {
        val useArCoreDepthApi = _uiState.value.arCoreDepthEnabled && !forceSafeCameraConfig
        if (useArCoreDepthApi && s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            config.depthMode = Config.DepthMode.AUTOMATIC
            _uiState.update { it.copy(isDepthApiSupported = true) }
        } else {
            config.depthMode = Config.DepthMode.DISABLED
            _uiState.update { it.copy(isDepthApiSupported = false) }
        }
        renderer?.depthApiEnabled = useArCoreDepthApi
        return useArCoreDepthApi
    }

    /**
     * Selects the mono camera config for [chosenFps] without repeating the old wedge: keeps ARCore's
     * default config ([defaultCfg]) — the broadly compatible one — and only swaps to a same-cameraId
     * variant that differs in fps. The session must not be resumed (a CameraConfig can only change on
     * a paused or not-yet-started session). [restoreDefaultWhenUnmatched] puts a live session that was
     * swapped earlier back on [defaultCfg] when the new choice has no variant of its own; at session
     * build the config already is the default, so there is nothing to restore.
     */
    private fun applyCameraTargetFps(
        s: Session,
        defaultCfg: CameraConfig,
        chosenFps: Int,
        restoreDefaultWhenUnmatched: Boolean,
    ) {
        val fpsConfig = try {
            when (chosenFps) {
                // DEVICE_DEFAULT: don't filter at all. ARCore's own choice is the
                // broadest-compatible config it offers, and asking for it explicitly is
                // different from asking for 30 and happening to get it.
                CameraTargetFps.DEVICE_DEFAULT -> null
                // DEVICE_MAX: every config for this camera, highest frame rate wins.
                // Compared on the range's upper bound because a config advertising
                // 15-60 outranks a fixed 30 for a user who asked for maximum.
                CameraTargetFps.DEVICE_MAX ->
                    s.getSupportedCameraConfigs(CameraConfigFilter(s).apply {
                        facingDirection = CameraConfig.FacingDirection.BACK
                    }).filter { it.cameraId == defaultCfg.cameraId }
                        .maxByOrNull { it.fpsRange.upper }
                else ->
                    s.getSupportedCameraConfigs(CameraConfigFilter(s).apply {
                        facingDirection = CameraConfig.FacingDirection.BACK
                        this.targetFps = EnumSet.of(
                            if (chosenFps == CameraTargetFps.FPS_60)
                                CameraConfig.TargetFps.TARGET_FPS_60
                            else CameraConfig.TargetFps.TARGET_FPS_30,
                        )
                    }).firstOrNull { it.cameraId == defaultCfg.cameraId }
            }
        } catch (e: Exception) {
            Timber.w(e, "camera fps config query failed")
            null
        }
        if (fpsConfig != null) {
            s.cameraConfig = fpsConfig
            appendDiag(
                "camera fps: ${CameraTargetFps.label(chosenFps)} -> " +
                    "${fpsConfig.fpsRange.lower}-${fpsConfig.fpsRange.upper}",
            )
            return
        }
        if (restoreDefaultWhenUnmatched) s.cameraConfig = defaultCfg
        if (chosenFps == CameraTargetFps.DEVICE_DEFAULT) {
            appendDiag("camera fps: device default (${defaultCfg.fpsRange.upper} max) — no swap")
        } else {
            appendDiag(
                "camera fps: no ${CameraTargetFps.label(chosenFps)} match for default cfg — using default",
            )
        }
    }

    // What the live ARCore session was last configured with, so a settings change only touches the
    // session when the effective value actually differs. Guarded by sessionMutex; set at session
    // build, cleared at teardown. appliedCameraFps is null when no fps selection is in force
    // (hardware stereo or camera safe mode), and sessionDefaultCameraConfig is ARCore's own pick.
    private var appliedDepthRequested: Boolean? = null
    private var appliedCameraFps: Int? = null
    private var sessionDefaultCameraConfig: CameraConfig? = null

    /**
     * Applies the session-level AR settings to the LIVE ARCore session the moment they change, instead
     * of leaving them for the next AR entry: [checkDepth] re-applies "ARCore depth" (Session.configure,
     * still gated by isDepthModeSupported), [checkCameraFps] re-selects the camera config for the
     * camera target fps / adaptive-rate cap. Same ownership dance as [recoverFromBrokenStereo]: get the
     * GL thread out of the frame body first (Session is not thread-safe), pause only when the camera
     * config has to change, then resume and re-attach. A camera-config swap restarts ARCore tracking;
     * that is the cost of the artist's own change and the anchor relocalizes as on any resume.
     *
     * No-op without a session — the next session is built from the current settings anyway — and in
     * the two cases that override the setting by design: hardware stereo (fps is the stereo config's)
     * and camera safe mode after a dead-camera stall (default config, no depth, until next launch).
     */
    private fun reconcileLiveSessionSettings(
        checkDepth: Boolean = false,
        checkCameraFps: Boolean = false,
    ) {
        if (session == null) return
        viewModelScope.launch(dispatchers.default) {
            sessionMutex.withLock {
                val s = session ?: return@withLock
                if (isDestroying) return@withLock
                val state = _uiState.value
                val safeMode = forceSafeCameraConfig
                val wantDepth = state.arCoreDepthEnabled && !safeMode
                val wantFps = if (safeMode || state.isHardwareStereoActive) {
                    null
                } else {
                    effectiveCameraTargetFps(
                        targetFps = state.cameraTargetFps,
                        adaptiveRateEnabled = state.adaptiveRateEnabled,
                        batteryTier = state.batteryTier,
                    )
                }
                if (checkDepth && safeMode && state.arCoreDepthEnabled) {
                    appendDiag("settings: ARCore depth held off by camera safe mode (stream stalled earlier)")
                }
                val depthChanged = checkDepth && wantDepth != appliedDepthRequested
                val fpsChanged = checkCameraFps && wantFps != null && wantFps != appliedCameraFps
                if (!depthChanged && !fpsChanged) return@withLock

                val handoffSucceeded = renderer?.detachSessionBounded(1500L) ?: true
                if (
                    decideSessionOwnership(handoffSucceeded, SessionOwnershipAction.RECONFIGURE) ==
                    SessionOwnershipDecision.ABORT_RECONFIGURE
                ) {
                    appendDiag("settings: GL thread still owns ARCore after 1500ms — live reconfigure aborted")
                    _feedback.tryEmit(
                        com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(
                            appContext.getString(DesignR.string.ar_feedback_camera_setting_apply_failed)
                        )
                    )
                    return@withLock
                }
                val wasResumed = isSessionResumed
                try {
                    if (fpsChanged && wantFps != null) {
                        if (wasResumed) pauseArSessionInternal()
                        applyCameraTargetFps(
                            s,
                            sessionDefaultCameraConfig ?: s.cameraConfig,
                            wantFps,
                            restoreDefaultWhenUnmatched = true,
                        )
                        appliedCameraFps = wantFps
                    }
                    // Depth is re-asserted after any camera-config change too: support is per config.
                    val config = s.config
                    appliedDepthRequested = applyArCoreDepthMode(s, config)
                    s.configure(config)
                    appendDiag(
                        "settings: live session reconfigured " +
                            "(depth=${if (appliedDepthRequested == true) "on" else "off"}" +
                            (if (fpsChanged) ", fps=${CameraTargetFps.label(wantFps ?: 0)}" else "") + ")",
                    )
                } catch (e: Exception) {
                    Timber.e(e, "ARDIAG live settings reconfigure failed")
                    appendDiag("settings: live reconfigure failed (${e.javaClass.simpleName})")
                } finally {
                    // Always resume what was running — a throw mid-swap must not leave a black camera.
                    if (wasResumed && !isSessionResumed && isActivityResumed && isInArMode && !isDestroying) {
                        resumeArSessionInternal()
                    }
                    // attachSession resets the camera-streaming watchdog, so a new config earns a fresh
                    // CAMERA STREAMING / STALL verdict.
                    renderer?.attachSession(session)
                    lastTrackingTimestampMs = System.currentTimeMillis()
                }
            }
        }
    }

    /**
     * One-time dual-lens capability probe. Runs a short throwaway ARCore session configured for forced
     * hardware stereo with the Depth API enabled, in an isolated ":probe" process, and adopts dual-lens
     * only if the session confirms the stereo stream is in force AND, under it, reaches TRACKING and
     * hands back a populated depth image (see StereoProbeService for why that cannot prove the depth
     * came from disparity rather than ML). A device that merely lists a stereo config but stalls under
     * it (or thrashes and never tracks) is cached as "mono". A probe that could not open the camera is
     * NOT a verdict: nothing is persisted and the next AR entry probes again. Must be called while no
     * live session holds the camera (after the camera gate).
     *
     * Returns true when the probe actually ran (and so may still be releasing the camera).
     */
    private suspend fun probeStereoCapability(context: Context): Boolean {
        val capability = _uiState.value
        if (
            !capability.isArCoreAvailabilityResolved ||
            !capability.isArCoreAvailable
        ) {
            appendDiag(
                "probe: skipped; ARCore-only depth probe requires positively resolved ARCore capability",
            )
            return false
        }
        if (stereoCapable != null) return false
        if (!stereoProbeInFlight.compareAndSet(false, true)) return false
        try {
            // On-screen so the black "session null" startup window is explained: the probe holds the
            // camera exclusively while it runs, delaying the live session by up to its timeout.
            appendDiag("probe: start (camera held ~${StereoProbeService.PROBE_TIMEOUT_MS / 1000}s max)")
            val result = runProbeViaService(context)
            val persisted = StereoProbeService.persistedCapabilityFor(result)
            if (persisted == null) {
                // The probe could not open the camera: that says nothing about the lenses. Leave the
                // device unprobed (mono for this entry) and probe again on the next AR entry.
                appendDiag("probe: camera unavailable — no verdict, will retry next AR entry")
                Timber.i("ARDIAG depth-triangulation probe inconclusive: camera unavailable")
                return true
            }
            val capable = persisted == 1
            stereoCapable = capable
            settingsRepository.setStereoCapability(persisted)
            appendDiag("probe: done capable=$capable")
            Timber.i("ARDIAG depth-triangulation probe complete: capable=$capable")
        } catch (e: Exception) {
            // A cancelled probe (AR exit / pause) must NOT be cached as a permanent mono verdict —
            // let cancellation propagate so the device stays "unprobed" and re-probes next entry.
            if (e is kotlinx.coroutines.CancellationException) throw e
            stereoCapable = false
            settingsRepository.setStereoCapability(0)
            Timber.w(e, "ARDIAG depth-triangulation probe threw -> treating device as mono")
        } finally {
            stereoProbeInFlight.set(false)
        }
        return true
    }

    /**
     * Drives [StereoProbeService] in the isolated ":probe" process and returns its verdict. The broken
     * forced-stereo thrash (if any) happens entirely in that throwaway background process, so it can
     * never starve this UI process into an ANR. If the probe process dies, the binding can't be made,
     * or no reply arrives within the timeout, we conservatively return false (stay on mono).
     */
    private suspend fun runProbeViaService(context: Context): Int {
        val appCtx = context.applicationContext
        val incapable = StereoProbeService.RESULT_INCAPABLE
        val result = kotlinx.coroutines.CompletableDeferred<Int>()
        val replyMessenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
            if (msg.what == StereoProbeService.MSG_RESULT) result.complete(msg.arg1)
            true
        })
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    val m = Message.obtain(null, StereoProbeService.MSG_RUN_PROBE).apply {
                        replyTo = replyMessenger
                    }
                    Messenger(binder).send(m)
                } catch (_: Exception) {
                    result.complete(incapable)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // The :probe process died (e.g. killed after a hang) -> treat the device as mono.
                result.complete(incapable)
            }
        }
        val bound = try {
            appCtx.bindService(
                Intent(appCtx, StereoProbeService::class.java),
                conn,
                Context.BIND_AUTO_CREATE
            )
        } catch (_: Exception) {
            false
        }
        if (!bound) {
            try { appCtx.unbindService(conn) } catch (_: Exception) {}
            return incapable
        }
        return try {
            // Bound process startup + the probe's own timeout, with headroom; never block forever.
            kotlinx.coroutines.withTimeoutOrNull(StereoProbeService.PROBE_TIMEOUT_MS + 4_000L) {
                result.await()
            } ?: incapable
        } finally {
            try { appCtx.unbindService(conn) } catch (_: Exception) {}
        }
    }

    /**
     * Save the rectified page that lets the standalone SphereSLAM path relocalize this project on a
     * later visit.
     *
     * The image is written to a new versioned file first. Only then does the repository atomically
     * commit URI + width + metric flag together. The old file is deleted only after that metadata
     * commit succeeds. A project switch or competing recapture makes the transform a no-op and the
     * uncommitted new file is removed instead.
     */
    fun saveSphereSlamReference(
        bitmap: Bitmap,
        referenceWidthMeters: Float = 1f,
        physicallyMetric: Boolean = false,
    ) {
        require(referenceWidthMeters.isFinite() && referenceWidthMeters > 0f)
        val startingProject = projectRepository.currentProject.value ?: return
        val projectId = startingProject.id
        val expectedPreviousUri = startingProject.sphereSlamReferenceUri
        val supersededAtlasPages = startingProject.sphereSlamAtlasPages

        viewModelScope.launch(dispatchers.io) {
            var newUri: android.net.Uri? = null
            var transformApplied = false
            try {
                // Doodling is part of target creation, not an optional enhancement.
                // Teleological SLAM consumes this seed for relocalization/progress, so never persist a
                // standalone reference that cannot also produce its canonical-page doodle.
                val standaloneFingerprint =
                    StandaloneFingerprintBuilder.build(
                        slam = slamManager,
                        bitmap = bitmap,
                        referenceWidthMeters = referenceWidthMeters,
                        features = _uiState.value.doodleFeatures,
                    ) ?: error("Standalone target produced no teleological SLAM fingerprint")

                newUri = projectManager.saveSphereSlamReference(appContext, projectId, bitmap)
                val candidateUri = newUri
                projectRepository.updateProject { current ->
                    if (
                        current.id != projectId ||
                        current.sphereSlamReferenceUri != expectedPreviousUri
                    ) {
                        current
                    } else {
                        transformApplied = true
                        lastStandaloneGuideKey = null
                        // Doodle replacement changes only the precision/teleological layer.
                        // The base SphereSLAM photosphere and its orientation history stay alive.
                        current.copy(
                            sphereSlamReferenceUri = candidateUri,
                            sphereSlamReferenceWidthMeters = referenceWidthMeters,
                            sphereSlamReferencePhysicallyMetric = physicallyMetric,
                            sphereSlamAnchorGeneration = current.sphereSlamAnchorGeneration + 1L,
                            sphereSlamAnchorFrameVersion =
                                com.hereliesaz.graffitixr.common.model.SPHERE_SLAM_FINGERPRINT_FRAME_VERSION,
                            sphereSlamPlacementAnchorGeneration = 0L,
                            sphereSlamFingerprint = standaloneFingerprint,
                            // A new target may be another wall: its normal is learned afresh.
                            doodleWallNormalEnu = emptyList(),
                            sphereSlamFingerprintFrameVersion =
                                com.hereliesaz.graffitixr.common.model.SPHERE_SLAM_FINGERPRINT_FRAME_VERSION,
                            // Precision target replacement never resets the base photosphere. Attach
                            // the new doodle with the capture-time provisional transform when
                            // available; otherwise leave it unresolved until the same-frame solver
                            // publishes an exact bridge.
                            sphereSlamMapFromFingerprint =
                                pendingStandaloneFingerprintPlacement
                                    ?.mapFromFingerprint
                                    ?.takeIf { it.size == 16 && it.all(Float::isFinite) }
                                    ?.toList()
                                    ?: emptyList(),
                            sphereSlamWallFeatureMap = null,
                            sphereSlamWallFeatureMapFrameVersion =
                                com.hereliesaz.graffitixr.common.model.SPHERE_SLAM_FINGERPRINT_FRAME_VERSION,
                            sphereSlamAtlasPages = emptyList(),
                        )
                    }
                }

                if (transformApplied) {
                    // A new target: its wall normal is learned afresh.
                    slamManager.setWallNormalEnu(null)
                    projectManager.deleteSphereSlamReference(
                        appContext,
                        projectId,
                        expectedPreviousUri,
                    )
                    supersededAtlasPages.forEach { page ->
                        projectManager.deleteSphereSlamAtlasPage(
                            appContext,
                            projectId,
                            page.referenceUri,
                        )
                    }
                } else {
                    projectManager.deleteSphereSlamReference(appContext, projectId, newUri)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // updateProject persists project.json before it publishes currentProject. A
                // cancellation can therefore arrive after either half of that commit. Reconcile
                // against both in-memory and on-disk metadata before deleting either image.
                withContext(NonCancellable) {
                    reconcileInterruptedSphereSlamReferenceSave(
                        projectId = projectId,
                        expectedPreviousUri = expectedPreviousUri,
                        candidateUri = newUri,
                    )
                }
                throw e
            } catch (e: Exception) {
                // The same ambiguity exists for an exception thrown after the atomic project.json
                // replacement but before updateProject returns. Never decide from a local boolean
                // alone whether the candidate is safe to delete.
                withContext(NonCancellable) {
                    reconcileInterruptedSphereSlamReferenceSave(
                        projectId = projectId,
                        expectedPreviousUri = expectedPreviousUri,
                        candidateUri = newUri,
                    )
                }
                Timber.e(e, "Failed to persist standalone SphereSLAM reference")
                _feedback.tryEmit(
                    com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(
                        appContext.getString(DesignR.string.ar_feedback_sphere_target_save_failed),
                        e,
                    ),
                )
            }
        }
    }

    /**
     * Resolve an interrupted standalone-reference swap from authoritative state.
     *
     * [ProjectRepositoryImpl.updateProject] writes project.json before publishing currentProject,
     * so interruption can leave either source one step ahead of the other. If either source already
     * points at [candidateUri], the candidate is committed and must be kept; the superseded image can
     * be removed. Otherwise the candidate never became authoritative and is safe to delete.
     */
    private suspend fun reconcileInterruptedSphereSlamReferenceSave(
        projectId: String,
        expectedPreviousUri: android.net.Uri?,
        candidateUri: android.net.Uri?,
    ) {
        val candidate = candidateUri ?: return
        val currentCommitted =
            projectRepository.currentProject.value?.let { current ->
                current.id == projectId && current.sphereSlamReferenceUri == candidate
            } == true
        val diskCommitted = runCatching {
            projectManager.loadProjectMetadata(appContext, projectId)
                ?.sphereSlamReferenceUri == candidate
        }.getOrDefault(false)

        if (currentCommitted || diskCommitted) {
            projectManager.deleteSphereSlamReference(
                appContext,
                projectId,
                expectedPreviousUri,
            )
        } else {
            projectManager.deleteSphereSlamReference(appContext, projectId, candidate)
        }
    }

    /**
     * Clear only the standalone reference fields when the URI that failed to restore is still the
     * project's current reference. A newer recapture wins the compare and is left untouched.
     */
    fun clearSphereSlamReferenceIfMatches(expectedUri: android.net.Uri) {
        val startingProject = projectRepository.currentProject.value ?: return
        val projectId = startingProject.id
        val atlasPages = startingProject.sphereSlamAtlasPages
        viewModelScope.launch(dispatchers.io) {
            var cleared = false
            try {
                projectRepository.updateProject { current ->
                    if (
                        current.id != projectId ||
                        current.sphereSlamReferenceUri != expectedUri
                    ) {
                        current
                    } else {
                        cleared = true
                        current.copy(
                            sphereSlamReferenceUri = null,
                            sphereSlamReferenceWidthMeters = 1f,
                            sphereSlamReferencePhysicallyMetric = false,
                            sphereSlamAnchorGeneration = current.sphereSlamAnchorGeneration + 1L,
                            sphereSlamAnchorFrameVersion =
                                com.hereliesaz.graffitixr.common.model.SPHERE_SLAM_FINGERPRINT_FRAME_VERSION,
                            sphereSlamPlacementAnchorGeneration = 0L,
                            // The seed's object points are defined by this exact page. Keeping it
                            // after the page is gone would let a later standalone runtime restore a
                            // coordinate frame it can no longer reconstruct or verify.
                            sphereSlamFingerprint = null,
                            sphereSlamMapFromFingerprint = emptyList(),
                            sphereSlamWallFeatureMap = null,
                            sphereSlamWallFeatureMapFrameVersion =
                                com.hereliesaz.graffitixr.common.model.SPHERE_SLAM_FINGERPRINT_FRAME_VERSION,
                            sphereSlamAtlasPages = emptyList(),
                        )
                    }
                }
                if (cleared) {
                    projectManager.deleteSphereSlamReference(appContext, projectId, expectedUri)
                    atlasPages.forEach { page ->
                        projectManager.deleteSphereSlamAtlasPage(
                            appContext,
                            projectId,
                            page.referenceUri,
                        )
                    }
                    appendDiag(
                        "SphereSLAM standalone cleared missing/corrupt persisted reference",
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to clear invalid standalone SphereSLAM reference")
            }
        }
    }

    /**
     * Drop one corrupt/incompatible grown page without disturbing canonical page 0 or its frame.
     * Compare both stable page ID and URI so a stale decode callback cannot delete a recaptured page.
     */
    fun clearSphereSlamAtlasPageIfMatches(pageNo: Int, expectedUri: android.net.Uri) {
        val projectId = projectRepository.currentProject.value?.id ?: return
        viewModelScope.launch(dispatchers.io) {
            var removed = false
            try {
                projectRepository.updateProject { current ->
                    if (current.id != projectId) {
                        current
                    } else {
                        val matching = current.sphereSlamAtlasPages.any {
                            it.pageNo == pageNo && it.referenceUri == expectedUri
                        }
                        if (!matching) {
                            current
                        } else {
                            removed = true
                            current.copy(
                                sphereSlamAtlasPages = current.sphereSlamAtlasPages.filterNot {
                                    it.pageNo == pageNo && it.referenceUri == expectedUri
                                },
                            )
                        }
                    }
                }
                if (removed) {
                    projectManager.deleteSphereSlamAtlasPage(
                        appContext,
                        projectId,
                        expectedUri,
                    )
                    appendDiag("SphereSLAM atlas page removed page=$pageNo")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to clear invalid SphereSLAM atlas page")
            }
        }
    }

    /**
     * Commit a live-grown KPM page without ever changing canonical page 0.
     *
     * The PNG is installed first, then its stable page ID + canonical transform are committed
     * atomically to project.json. If the project/page-0 changed while IO was in flight, the new file
     * is discarded and the stale candidate cannot contaminate the new atlas.
     */
    fun saveSphereSlamAtlasPage(
        bitmap: Bitmap,
        pageNo: Int,
        referenceWidthUnits: Float,
        physicallyMetric: Boolean,
        canonicalFromPage: FloatArray,
    ) {
        val startingProject = projectRepository.currentProject.value ?: return
        val projectId = startingProject.id
        val canonicalUri = startingProject.sphereSlamReferenceUri ?: return
        if (
            pageNo <= 0 ||
            !referenceWidthUnits.isFinite() ||
            referenceWidthUnits <= 0f ||
            canonicalFromPage.size != 16 ||
            canonicalFromPage.any { !it.isFinite() }
        ) return
        if (startingProject.sphereSlamAtlasPages.any { it.pageNo == pageNo }) return
        val pageTransform = canonicalFromPage.copyOf()

        viewModelScope.launch(dispatchers.io) {
            var uri: android.net.Uri? = null
            var committed = false
            try {
                uri = projectManager.saveSphereSlamAtlasPage(
                    appContext,
                    projectId,
                    pageNo,
                    bitmap,
                )
                val savedUri = requireNotNull(uri)
                val page = com.hereliesaz.graffitixr.common.model.SphereSlamAtlasPage(
                    pageNo = pageNo,
                    referenceUri = savedUri,
                    referenceWidthMeters = referenceWidthUnits,
                    physicallyMetric = physicallyMetric,
                    canonicalFromPage = pageTransform.toList(),
                )
                projectRepository.updateProject { current ->
                    if (
                        current.id != projectId ||
                        current.sphereSlamReferenceUri != canonicalUri ||
                        current.sphereSlamAtlasPages.any { it.pageNo == pageNo }
                    ) {
                        current
                    } else {
                        committed = true
                        current.copy(
                            sphereSlamAtlasPages =
                                (current.sphereSlamAtlasPages + page).sortedBy { it.pageNo },
                        )
                    }
                }
                if (committed) {
                    appendDiag(
                        "SphereSLAM atlas page persisted page=" + pageNo +
                            " frame=canonical-centered-page",
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to persist grown SphereSLAM atlas page")
            } finally {
                if (!committed && uri != null) {
                    projectManager.deleteSphereSlamAtlasPage(appContext, projectId, uri)
                }
            }
        }
    }

    fun setCameraPermission(granted: Boolean) {
        _uiState.update { it.copy(hasCameraPermission = granted) }
    }

    /**
     * `Session.resume()` called directly from here used to race the GL thread, which is inside
     * `ArRenderer.onDrawFrame` calling `session.update()` and other Session methods under its own
     * `sessionLock` — ARCore's Session is documented not thread-safe, and this exact class of race
     * produced a native SIGSEGV on ARCore's MTC_vio thread elsewhere in this app (see
     * `ArRenderer.updateFlashlight`'s doc). Routed through `ArRenderer.requestResume()` so it happens
     * under that same lock instead.
     *
     * Falls back to calling `resume()` directly — same as before this fix — when
     * `ArRenderer.requestResume()` reports [SessionLifecycleOutcome.NoSession], which covers TWO
     * cases: no renderer is attached at all (before the GLSurfaceView exists for this AR entry), or a
     * renderer IS attached but its own session reference was already nulled by a caller that ran
     * `detachSessionBounded` first specifically so it could touch the session directly afterwards
     * (see `recoverFromBrokenStereo`, which does exactly that before calling this). Either way there
     * is no GL thread that could be concurrently driving `onDrawFrame` against THIS session, so a
     * direct call is safe — the race this guards against requires a renderer that still holds the
     * live session.
     *
     * [SessionLifecycleOutcome.LockTimeout] (the GL thread was still inside `onDrawFrame` holding the
     * session lock when the bounded wait expired) is retried a bounded number of times via
     * [retryOnLockTimeout] before giving up — a single slow frame finishing between attempts is the
     * common case. If every retry still times out, the user is told rather than being left staring at
     * a camera that silently never resumed (previously this branch only logged, leaving
     * `isSessionResumed` false with nothing visible and nothing retrying).
     */
    private fun resumeArSessionInternal() {
        val s = session ?: return
        val r = renderer
        try {
            val outcome = r?.let { renderer -> retryOnLockTimeout { renderer.requestResume() } }
            when (outcome) {
                null, is SessionLifecycleOutcome.NoSession -> {
                    s.resume()
                    isSessionResumed = true
                    startAutoSave()
                }
                is SessionLifecycleOutcome.Applied -> {
                    isSessionResumed = true
                    startAutoSave()
                }
                is SessionLifecycleOutcome.LockTimeout -> {
                    Timber.e(
                        "Failed to resume ARCore session: GL thread did not release its session " +
                            "lock after ${LOCK_TIMEOUT_MAX_RETRIES} retries"
                    )
                    _feedback.tryEmit(
                        com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(
                            appContext.getString(DesignR.string.ar_feedback_camera_not_resumed)
                        )
                    )
                }
                is SessionLifecycleOutcome.Failed -> throw outcome.error
            }
        } catch (e: CameraNotAvailableException) {
            Timber.e(e, "Camera not available for ARCore")
            // Permission revoked at runtime, or the camera is held elsewhere. Tell the user
            // rather than leaving a frozen preview.
            _feedback.tryEmit(
                com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(appContext.getString(DesignR.string.ar_feedback_camera_permission), e)
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to resume ARCore session")
        }
    }

    /**
     * Same race, same fix, same NoSession fallback reasoning as [resumeArSessionInternal] — see its
     * doc, including the [SessionLifecycleOutcome.LockTimeout] retry-then-notify handling.
     */
    private fun pauseArSessionInternal() {
        val s = session ?: return
        val r = renderer
        try {
            stopAutoSave()
            val outcome = r?.let { renderer -> retryOnLockTimeout { renderer.requestPause() } }
            when (outcome) {
                null, is SessionLifecycleOutcome.NoSession -> {
                    s.pause()
                    isSessionResumed = false
                }
                is SessionLifecycleOutcome.Applied -> {
                    isSessionResumed = false
                }
                is SessionLifecycleOutcome.LockTimeout -> {
                    Timber.e(
                        "Failed to pause ARCore session: GL thread did not release its session " +
                            "lock after ${LOCK_TIMEOUT_MAX_RETRIES} retries"
                    )
                    _feedback.tryEmit(
                        com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(
                            appContext.getString(DesignR.string.ar_feedback_camera_not_paused)
                        )
                    )
                }
                is SessionLifecycleOutcome.Failed -> throw outcome.error
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to pause ARCore session")
        }
    }

    /**
     * Pause/close the current session and drop the renderer this teardown OWNS ([ownedRenderer], read
     * when the teardown was requested). Only what it owns is cleared: a renderer attached for the next
     * AR entry while this ran is left attached (it was handed no session — attachSessionToRenderer
     * withholds the closing one while [isDestroying]), and if AR mode was re-entered meanwhile a fresh
     * session is scheduled once the old one is gone.
     */
    private suspend fun performFullCleanupLocked(ownedRenderer: ArRenderer?) {
        isDestroying = true
        sessionMutex.withLock {
            stopAutoSave()
            // Stop the renderer touching ARCore BEFORE pause()/close(). The renderer drives the
            // session under its OWN ReentrantLock, so closing under sessionMutex alone raced the
            // GL frame body (hitTest/acquireCameraImage/setCameraTextureName on a session being
            // closed) — Session is not thread-safe, and that race segfaulted ARCore internally on
            // its MTC_vio thread. detachSessionBounded is a real ownership handoff: success means
            // the GL thread is out of the frame; timeout means it still owns the Session and native
            // teardown must be deferred. Saves still work: they read the renderer's sub-renderers,
            // not the ARCore session.
            val r = ownedRenderer
            val s = session
            val wasResumed = isSessionResumed
            r?.isDestroying = true
            val handoffSucceeded = r?.detachSessionBounded(1500L) ?: true

            saveMapBlocking()
            when (decideSessionOwnership(handoffSucceeded, SessionOwnershipAction.CLEANUP)) {
                SessionOwnershipDecision.PROCEED -> {
                    s?.let { closeDetachedArSession(it, wasResumed) }
                }
                SessionOwnershipDecision.DEFER_CLEANUP -> {
                    appendDiag("cleanup: GL thread still owns ARCore after 1500ms — deferring native close")
                    if (r != null && s != null) {
                        deferArSessionCloseUntilRendererHandoff(r, s, wasResumed)
                    }
                }
                SessionOwnershipDecision.ABORT_RECONFIGURE -> {
                    appendDiag("cleanup: ownership policy aborted native teardown")
                }
            }
            if (session === s) session = null
            // Only the renderer this teardown owns. A newer one belongs to the next AR entry; nulling
            // it here left that entry rendering with no ViewModel link (and, before the attach guard,
            // holding this closing session).
            if (renderer === r) renderer = null
            isSessionResumed = false
            appliedDepthRequested = null
            appliedCameraFps = null
            sessionDefaultCameraConfig = null
            _isCameraInUseByAr.value = false
            isDestroying = false
            clearCaptureState()
        }
        // AR was re-entered (or the activity recreated, e.g. rotation) while this teardown held the
        // mutex: that entry's session update saw isDestroying and skipped creation. Schedule it now.
        if (isInArMode && !isCleared) updateSessionStateLocked()
    }

    /**
     * Drops the large capture bitmaps and the native depth buffer held in [ArUiState], letting
     * GC reclaim them. We deliberately do NOT call recycle(): on AR-mode teardown a background
     * doodle/save coroutine (onConfirmDoodleCreation, updatePaintingGuide)
     * may still hold these same bitmap instances, and recycling them out from under it would
     * cause a "trying to use a recycled bitmap" crash. Nulling the references is deterministic
     * and safe; the platform reclaims the memory once no coroutine references them.
     */
    private fun clearCaptureState() {
        pendingTapPosition = null
        pendingStandaloneFingerprintPlacement = null
        resetCapturePipeline()
        _uiState.update {
            it.copy(
                tempCaptureBitmap = null,
                annotatedCaptureBitmap = null,
                doodleRawBitmap = null,
                doodleDepthBuffer = null,
                tapMarks = emptyList(),
                unwarpPoints = emptyList()
            )
        }
    }

    /**
     * Persists the wall feature map and SUSPENDS until it is written (it used to fire a coroutine and
     * return at once, so teardown closed the session — and a backend switch cleared the native map —
     * before the save had read anything).
     */
    @Volatile private var lastProgressSaveFailureNoticeMs = 0L

    /**
     * Tell the artist a wall-map / progress / Doodle write failed. Once a minute at most: an autosave
     * that keeps failing (a full disk) would otherwise say it on every tick.
     */
    private fun reportProgressSaveFailure(e: Exception) {
        val now = System.currentTimeMillis()
        if (now - lastProgressSaveFailureNoticeMs < PROGRESS_SAVE_FAILURE_NOTICE_MS) return
        lastProgressSaveFailureNoticeMs = now
        _feedback.tryEmit(
            com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(
                appContext.getString(DesignR.string.ar_feedback_progress_save_failed), e
            )
        )
    }

    private suspend fun saveMapBlocking() {
        val projectId = loadedProjectId ?: return
        // The wall feature map is the only thing here that survives a session meaningfully: it is
        // re-registered on load through the doodle + PnP relocalization, so its coordinates are
        // recoverable.
        //
        // The ARCore point cloud used to be written alongside it and restored on open. That could
        // not work: the file holds raw ARCore world coordinates, and a new session's world origin is
        // wherever the device happened to start, so every restored point landed at an arbitrary
        // rigid offset from the wall it was scanned off — and then appeared to drift as the artist
        // moved, because nothing re-registered it. It is a scan VISUALISATION (relocalization rides
        // the doodle, not the cloud), so the honest fix is not to persist it at all.
        try {
            withContext(dispatchers.io) { saveProjectWallMap() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Wall feature map save failed")
            reportProgressSaveFailure(e)
            return
        }

        lastSavedMapPointCount.set(slamManager.getMapPointCount())
        Timber.d("Atomic persistence complete: saved the wall feature map for $projectId")
    }

    fun saveMapNow() {
        loadedProjectId ?: return
        if (isSaving.get()) return

        viewModelScope.launch(Dispatchers.IO) {
            isSaving.set(true)
            try {
                saveMapBlocking()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Background map save failed")
            } finally {
                isSaving.set(false)
            }
        }
    }

    /**
     * Phase 3b: persist the in-session passive feature map into the project record (.gxr), preserving the
     * rest of the current project. No-op when building is off / the map is empty. Async + best-effort.
     */
    suspend fun saveProjectWallMap(outgoing: com.hereliesaz.graffitixr.common.model.GraffitiProject? = null) {
        // [outgoing]: the project being switched AWAY from, whose state native still holds. The
        // repository already publishes the incoming one as current, so the outgoing snapshot is written
        // whole (ProjectManager merges what it omits) instead of through the current-project transform.
        val projectId = outgoing?.id ?: loadedProjectId ?: return
        val currentProject = outgoing ?: projectRepository.currentProject.value ?: return
        if (currentProject.id != projectId) return
        suspend fun write(transform: (com.hereliesaz.graffitixr.common.model.GraffitiProject) ->
            com.hereliesaz.graffitixr.common.model.GraffitiProject) {
            if (outgoing != null) projectRepository.updateProject(transform(outgoing))
            else projectRepository.updateProject { project -> if (project.id == projectId) transform(project) else project }
        }
        val ui = _uiState.value
        val standaloneBackend =
            ui.isArCoreAvailabilityResolved &&
                !ui.isArCoreAvailable &&
                currentProject.sphereSlamReferenceUri != null &&
                currentProject.sphereSlamFingerprint != null
        // ARCore-frame state (wall normal, wall map, paint marks, grid) is what the loader swaps in;
        // between a project switch and the end of that swap native still holds the PREVIOUS
        // project's, and writing it here would put project A's map into project B. Skip until the
        // loader has claimed ownership; the next autosave tick picks it up. The standalone branch
        // below is installed by the standalone analyzer, not by that loader, and is guarded by its
        // own reference-URI / map_from_doodle checks.
        if (!standaloneBackend && !nativeStateOwner.owns(projectId)) {
            Timber.d("Wall map save skipped: native state is not (yet) project $projectId's")
            return
        }
        if (outgoing == null) saveWallNormalIfChanged(projectId, currentProject)

        val map = slamManager.getWallFeatureMap()?.takeIf { it.pointCount > 0 }

        if (standaloneBackend) {
            val standalonePaint = slamManager.getPaintMarks()
            val standaloneGrid = slamManager.exportPaintGrid()?.let { java.util.Base64.getEncoder().encodeToString(it) }
            val standaloneGrown = slamManager.getGrownWall()
            if (map == null && standalonePaint == null && standaloneGrid == null && standaloneGrown == null) return
            val mapFromFingerprint = currentProject.sphereSlamMapFromFingerprint
            // The frame check: the map's co-registration, or (no map yet) the seeded Doodle's anchor.
            val nativeAnchor = map?.anchor ?: slamManager.getDoodleAnchor()
            if (
                mapFromFingerprint.size != 16 ||
                !sameStandaloneTransform(nativeAnchor, mapFromFingerprint)
            ) {
                appendDiag(
                    "SphereSLAM explicit precision-map save refused: " +
                        "native anchor does not match map_from_doodle",
                )
                return
            }
            write { project ->
                if (
                    project.id == projectId &&
                    project.sphereSlamReferenceUri == currentProject.sphereSlamReferenceUri
                ) {
                    project.copy(
                        sphereSlamWallFeatureMap = map ?: project.sphereSlamWallFeatureMap,
                        // Paint marks, area progress and grown rows live in the same page frame.
                        sphereSlamPaintMarks = standalonePaint ?: project.sphereSlamPaintMarks,
                        sphereSlamPaintGrid = standaloneGrid ?: project.sphereSlamPaintGrid,
                        sphereSlamGrownDoodle = standaloneGrown ?: project.sphereSlamGrownDoodle,
                        sphereSlamWallFeatureMapFrameVersion =
                            com.hereliesaz.graffitixr.common.model.SPHERE_SLAM_FINGERPRINT_FRAME_VERSION,
                        // Phase 1b: persist the per-keyframe orientation log alongside the map it is
                        // co-registered with. Null snapshot (flag off / nothing recorded) keeps the
                        // project's existing value (docs/SPHERESLAM_SPHERE_MAP.md).
                        sphereSlamKeyframeOrientations =
                            keyframeOrientationRecorder.snapshot()
                                ?: project.sphereSlamKeyframeOrientations,
                    )
                } else {
                    project
                }
            }
            lastStandaloneSavedMapRevision = slamManager.getWallFeatureMapRevision()
            lastStandaloneMapSaveMs = android.os.SystemClock.elapsedRealtime()
            lastSavedProgress = slamManager.getPaintingProgress()
            return
        }

        // ARCore/capture-frame persistence. Never execute this branch for the standalone native
        // map above: wallFeatureMap/paintMarks belong to the legacy ARCore doodle frame.
        val paint = slamManager.getPaintMarks()
        val grid = slamManager.exportPaintGrid()
            ?.let { java.util.Base64.getEncoder().encodeToString(it) }
        // What the Doodle grew this session (and the restored earlier growth it still carries).
        val grown = slamManager.getGrownWall()
        if (map == null && paint == null && grid == null && grown == null) return
        write { project ->
            if (project.id == projectId) {
                project.copy(
                    wallFeatureMap = map ?: project.wallFeatureMap,
                    paintMarks = paint ?: project.paintMarks,
                    paintGrid = grid ?: project.paintGrid,
                    grownDoodle = grown ?: project.grownDoodle,
                )
            } else {
                project
            }
        }
        paint?.let { lastSavedPaintCount.set(it.pointCount) }
        lastSavedWallRows.set(slamManager.getWallKeypointCount())
        lastSavedProgress = slamManager.getPaintingProgress()
    }

    /**
     * Persist the wall's normal MobileGS learned at its locks ([SlamManager.getWallNormalEnu]) when it
     * is new or has moved more than a couple of degrees, so the next session's first relocalization
     * can turn a lost frame by it.
     */
    private suspend fun saveWallNormalIfChanged(
        projectId: String,
        currentProject: com.hereliesaz.graffitixr.common.model.GraffitiProject,
    ) {
        val normal = slamManager.getWallNormalEnu() ?: return
        val saved = currentProject.doodleWallNormalEnu
        if (saved.size == 3) {
            val dot = saved[0] * normal[0] + saved[1] * normal[1] + saved[2] * normal[2]
            if (dot > WALL_NORMAL_RESAVE_COS) return
        }
        projectRepository.updateProject { project ->
            if (project.id == projectId) project.copy(doodleWallNormalEnu = normal.toList()) else project
        }
    }

    /**
     * The wall doodle currently live in native, retained so a design resize can repartition it
     * (`IMPLEMENTATION.md` 2.2) without a round trip through the project file.
     *
     * Held alongside the intrinsics/anchor/view it was restored with, because a repartition has to
     * re-push all of them — `restoreWallDoodleMetric` REPLACES the stored doodle, so
     * pushing regions alone would drop the co-registration and the rectification view with it.
     */
    private class LiveDoodle(
        val doodle: com.hereliesaz.graffitixr.common.model.Doodle,
        val anchor: FloatArray,
        val intrinsics: FloatArray,
        val view: FloatArray,
    )

    @Volatile private var liveDoodle: LiveDoodle? = null

    /**
     * Lets a newer partition request cancel an in-flight older one.
     *
     * **It does not serialize them, and an earlier version of this comment claimed it did.**
     * `cancel()` does not join and `Dispatchers.Default` is multi-threaded, so a cancelled job can
     * still finish. There IS an `ensureActive()` between the classify pass and the native push, so
     * the common cancellation lands there — but nothing checks between that call and
     * `restoreWallDoodleMetric`, so a cancel arriving in that window still completes its push.
     * What cancellation buys is that the newest design wins the usual case, which matters because a
     * drag fires every frame and each firing costs a repartition, a native replace and a write.
     *
     * The real protection against an out-of-order write is the idempotence check in the job body:
     * a partition that computes the same regions and extents makes no write at all.
     */
    // AtomicReference, not @Volatile. This stopped being single-threaded when partition-on-arrival
    // landed: the GL thread reaches it through onDesignFootprintChanged and the IO dispatcher
    // through partitionLiveDoodleIfNeeded. @Volatile gives visibility but NOT atomicity, so a
    // plain `cancel(); assign` still interleaves into "both threads cancel the same job, one
    // assignment is lost, and the orphan runs to completion alongside its replacement" — two jobs
    // pushing into native at once, which is precisely what the swap exists to prevent.
    private val partitionJob = java.util.concurrent.atomic.AtomicReference<Job?>(null)

    /**
     * The most recent design footprint the renderer published, or null before the artwork is placed.
     *
     * Kept because the two events that make a partition possible — the artwork being placed, and the
     * doodle becoming live — race, and the losing order was the common one. In a capture
     * session the doodle arrives only after detection, `saveProject` and `loadProject`, long
     * after the single footprint edge that follows confirmation; with only the callback, that
     * session's doodle was never partitioned at all.
     */
    @Volatile private var latestDesignFootprint: DoodlePartition.DesignFootprint? = null

    /**
     * The design pose the live partition was last computed against, or null when none has been.
     *
     * In memory rather than on the `Doodle`: the stored `captureHalfW`/`captureHalfH` already
     * answer "was this partitioned against a different SIZE", and adding a persisted pose would mean
     * a fourth parallel field to keep consistent for a question that only matters within a session.
     * Null after a reload forces exactly one partition, which is what should happen there regardless.
     */
    @Volatile private var lastPartitionedPose: FloatArray? = null

    /**
     * Raise or clear the "artwork leaves too little bare wall" warning, and toast on the rising edge.
     *
     * Extracted so the fresh-partition and already-up-to-date paths share one implementation: they
     * diverged once, and the reload path — where a project reopens already in that state and the
     * artist has no memory of being told — was the half that lost the message.
     *
     * Edge-triggered so a slow pinch through the threshold does not toast every frame. Saying it at
     * all matters: a `uiState` boolean nothing reads is the same failure as an operator nothing
     * calls, and its neighbour `legacyDoodleRefused` shipped exactly that way, with a KDoc
     * claiming it was "surfaced".
     */
    private fun publishBackboneWarning(backbone: Int) {
        val tooSmall = backbone in 0 until DoodlePartition.MIN_BACKBONE
        val wasTooSmall = _uiState.value.backboneTooSmall
        _uiState.update { it.copy(backboneTooSmall = tooSmall) }
        if (tooSmall && !wasTooSmall) {
            _feedback.tryEmit(
                com.hereliesaz.graffitixr.common.model.FeedbackEvent.Toast(
                    appContext.getString(DesignR.string.ar_feedback_backbone_warning, backbone),
                ),
            )
        }
    }

    /**
     * Element-wise, at a millimetre.
     *
     * Note this DOES walk the anchor-relative model matrix, which carries `R_anchorᵀ` and is
     * therefore drift-coupled — the very property that made the renderer's old trigger misfire. It
     * is harmless only because it never sees an unpublished value: every footprint reaching here was
     * one the renderer already decided to emit, so this compares a published pose against a copy of
     * a published pose. If it ever gains a caller that feeds it live per-frame poses, it becomes the
     * same bug.
     */
    private fun poseDiffers(pose: FloatArray, previous: FloatArray?): Boolean {
        if (previous == null || previous.size != pose.size) return true
        for (i in pose.indices) if (kotlin.math.abs(pose[i] - previous[i]) > 1e-3f) return true
        return false
    }

    /**
     * Partition the live doodle against the last known design footprint, if both exist and the
     * partition is missing or stale.
     *
     * The pull half of the pair above: called when the DOODLE arrives, where
     * [onDesignFootprintChanged] is called when the FOOTPRINT moves.
     */
    private fun partitionLiveDoodleIfNeeded() {
        val design = latestDesignFootprint ?: return
        onDesignFootprintChanged(design)
    }

    /**
     * The artwork was placed or resized: (re)partition the live doodle against where it now
     * sits (`IMPLEMENTATION.md` 2.2/2.4).
     *
     * **This is where the partition actually happens**, not at capture. Target creation is what
     * establishes the anchor the artwork will sit on, so a capture has no placed design to ask Φ
     * about; the first time the question has an answer is here.
     *
     * Fires on an unpartitioned doodle as well as a stale one. That is the case
     * `DoodlePartition.partition` was written for and the one the flow above guarantees:
     * `isPartitionStale` is defined as false when there is no partition yet, so gating on staleness
     * alone would mean a doodle that starts unpartitioned stays that way forever.
     *
     * Called from the GL thread; all work is dispatched off it.
     */
    /**
     * `IMPLEMENTATION.md` **4.5** — hand the engine the design's pose in the doodle's own
     * frame, which is what the corroboration match projects design features through.
     *
     * The composition is `captureAnchorCam · rigidModelAnchorLocal`, exactly as
     * [DoodlePartition.partition] builds it, and it is built here rather than in C++ for the
     * same reason Φ's is: neither factor is expressed in a world frame that drift or a session
     * boundary could invalidate, and re-deriving the product on the far side of JNI would be a
     * second chance to get the frame wrong with no test able to see it.
     *
     * Refuses on the same conditions `partition` refuses on. A doodle with no
     * `captureAnchorCam` (pre-Phase-2, or the depth path) has no frame to express a placement in,
     * and clearing is not a degradation — it returns corroboration to the global search it did
     * before Phase 4, which is a path that still works.
     */
    private fun pushDesignPlacement(
        doodle: com.hereliesaz.graffitixr.common.model.Doodle,
        design: DoodlePartition.DesignFootprint,
    ) {
        if (!design.isUsable || doodle.captureAnchorCam.size != 16) {
            slamManager.setDesignPlacement(null, 0f, 0f)
            return
        }
        slamManager.setDesignPlacement(
            RigidMath.multiply(
                doodle.captureAnchorCam.toFloatArray(), design.rigidModelAnchorLocal,
            ),
            design.halfW,
            design.halfH,
        )
    }

    fun onDesignFootprintChanged(design: DoodlePartition.DesignFootprint) {
        // Retained so the partition can also be driven the OTHER way round — see
        // [partitionLiveDoodleIfNeeded]. The footprint edge and the doodle's arrival are
        // two independent events and neither reliably comes second, so whichever is later has to be
        // able to start the work. Holding only a callback made this a one-shot that the capture
        // session always lost.
        // Refuse anything arriving after teardown began. The renderer re-checks its own
        // `isDestroying` before calling, but the two flags are set on different threads and this is
        // the assignment that matters: it is what teardown nulls two lines after clearing the
        // anchor, and a late frame re-arming it is the sixth-audit defect in one line.
        if (isDestroying) return
        latestDesignFootprint = design
        tryAutoFit()
        val live = liveDoodle ?: return
        val fp = live.doodle
        // IMPLEMENTATION.md 4.5 — push the placement on EVERY footprint change, not only when a
        // repartition is due. The two have different sensitivities: the partition only has to be
        // redone when the design moves far enough to change WHICH features sit under it, while the
        // corroboration match predicts a pixel per design feature, so a drag far too small to
        // restratify anything still puts every prediction in the wrong place. Gating this behind
        // `needsPartition` would leave the gated search aiming at where the design used to be —
        // silently, because a search in the wrong place reads as "the wall does not corroborate".
        //
        // Cheap enough to do unconditionally: one 4x4 multiply and a JNI call, at the
        // DesignMoveDetector's rate rather than the frame rate.
        pushDesignPlacement(fp, design)
        // Three ways this is worth doing, and the third is the one a size-only check misses.
        // `Doodle.isPartitionStale` compares extents, because extents are what it stores — so
        // a design the artist DRAGGED across the wall is not "stale" by that measure while being
        // completely wrong by Φ's. The pose it was last partitioned against is therefore tracked
        // here, in memory, rather than persisted: after a reload `lastPartitionedPose` is null,
        // which forces one partition, and that is the correct answer anyway.
        val needsPartition = fp.isUnpartitioned() ||
            fp.isPartitionStale(design.halfW, design.halfH) ||
            poseDiffers(design.rigidModelAnchorLocal, lastPartitionedPose)
        if (!needsPartition) return
        // Cancel rather than skip: the newest design size is the correct one, and an older in-flight
        // partition can only write a size the artist has already left behind.
        // LAZY so the job exists to be stored before it can run: started only after getAndSet has
        // published it, so a job can never be cancelled by a successor that has not yet seen it.
        // Captured at launch, checked before every write. `updateProject` applies to whatever is
        // current WHEN IT RUNS, not when it was queued, so a project switch during the partition
        // (a classify pass plus a blocking native replace) would write this doodle into the
        // wrong project's file and leave native holding the wrong map.
        val forProjectId = loadedDoodle?.projectId
        val next = viewModelScope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            val current = liveDoodle ?: return@launch
            val updated = DoodlePartition.partition(current.doodle, design)
            // Identity, not equality: `partition` returns the RECEIVER when it refuses (no points, no
            // captureAnchorCam, no usable extents), and re-pushing an unchanged doodle would
            // reset native's reloc state for nothing.
            if (updated === current.doodle) return@launch
            // The project may have changed while this ran; everything below writes.
            if (loadedDoodle?.projectId != forProjectId) return@launch
            // Idempotence, and it is load-bearing rather than an optimisation.
            //
            // `partition` returns a fresh copy whenever it CAN partition, so the identity check above
            // only catches refusals. Without this second check, `loadDoodleIfExists` →
            // partition → `updateProject` → a new `currentProject` emission →
            // `loadDoodleIfExists` again is a cycle: every project load paid for two partitions,
            // two native doodle replaces (each resetting reloc state) and two full project
            // writes. It terminated only because `Doodle.equals` compares `regions` by content
            // and `MutableStateFlow` conflates equal values — an accident of an override written for
            // a serialization test, one non-value-equal field away from an unbounded write loop.
            if (updated.regions.contentEquals(current.doodle.regions) &&
                updated.captureHalfW == current.doodle.captureHalfW &&
                updated.captureHalfH == current.doodle.captureHalfH
            ) {
                // Still record the pose: the partition is genuinely up to date for it, and not
                // recording it would re-enter here on every subsequent frame of a drag.
                lastPartitionedPose = design.rigidModelAnchorLocal.copyOf()
                // ...and still raise the warning. Skipping it here lost the "artwork covers nearly
                // all the wall" message on the RELOAD path — a project saved in that state reopened
                // silently, which is the one case where the artist has no memory of being told.
                // The partition is unchanged, not absent; its backbone count is just as real.
                publishBackboneWarning(DoodlePartition.backboneCount(updated))
                return@launch
            }
            ensureActive()

            val backbone = DoodlePartition.backboneCount(updated)
            slamManager.restoreWallDoodleMetric(
                updated.descriptorsData, updated.descriptorsRows, updated.descriptorsCols,
                updated.descriptorsType, updated.points3d.toFloatArray(),
                current.anchor, current.intrinsics,
                viewMatrix = current.view,
                regions = updated.regions,
            )
            liveDoodle = LiveDoodle(updated, current.anchor, current.intrinsics, current.view)
            lastPartitionedPose = design.rigidModelAnchorLocal.copyOf()
            // Native now holds exactly this, so the reload our own updateProject is about to trigger
            // must recognise it and not push it again.
            forProjectId?.let { loadedDoodle = LoadedDoodle(it, updated) }

            // IMPLEMENTATION.md 2.8, relocated. The capture cannot raise this — it has no footprint
            // to measure — so it is raised here, where the artwork's size is what made it true. A
            // warning rather than a refusal for the same reason: by this point the target exists and
            // the honest advice is "make the artwork smaller or step back", not "that never worked".
            publishBackboneWarning(backbone)

            // Persist so the next load starts from the size the artist settled on. Merged through
            // the repository transform rather than a full-object write, for the save-race reason
            // documented on the wall-map save above.
            // A write error (a full disk) must not take the app down from a viewModelScope launch: native
            // holds the partition for this session either way.
            try {
                projectRepository.updateProject { it.copy(doodle = updated) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Partitioned Doodle save failed")
                reportProgressSaveFailure(e)
                return@launch
            }
            Timber.i(
                "Design footprint %.3f x %.3f m; partitioned %d points, backbone %d",
                design.halfW, design.halfH, updated.points3d.size / 3, backbone,
            )
        }
        partitionJob.getAndSet(next)?.cancel()
        next.start()
    }


    /**
     * Forget the Kotlin-side state describing a partitionable doodle.
     *
     * `liveDoodle` left set is the dangerous one: a design move partitions the PREVIOUS
     * project's map and `updateProject` writes it into the CURRENT project's file.
     *
     * Call this from any path that leaves no partitionable map loaded, *including* one that leaves a
     * descriptors-only map in native. [dropLoadedDoodle] is the stronger form for paths that
     * must also empty native.
     */
    private fun dropPartitionState() {
        liveDoodle = null
        lastPartitionedPose = null
    }

    /**
     * Leave nothing loaded, in native or in Kotlin: no doodle, no marks centre, no partition
     * state.
     *
     * This exists because the alternative kept failing. The branches of `loadDoodleIfExists`
     * were fixed one at a time — no-doodle first, then the legacy refusal, and each time a
     * sibling was missed. The refusal branch got the Kotlin half and not `clearWallDoodle`, so
     * a project whose target was refused went on relocalizing against the PREVIOUS project's wall
     * and centring its overlay on the previous project's marks. Bundling the three operations means
     * a future branch cannot get one and forget the others.
     */
    private fun dropLoadedDoodle() {
        nativeStateOwner.clear()
        slamManager.clearWallDoodle()
        slamManager.overlayMarkCenterLocal = null
        // 0.9 — same lifetime: without it PoseFusion cannot compose a correction at all.
        slamManager.captureAnchorCam = null
        dropPartitionState()
    }

    /**
     * What is currently loaded into native, and which project it belongs to.
     *
     * The project id is the part that matters. Six consecutive audits found the same defect —
     * state belonging to project A surviving into project B — each in a different branch, each
     * found immediately after the previous one was patched. Naming the clearing operations was not
     * enough, because the bug is not "a branch forgot to clear"; it is that nothing tied the loaded
     * state to the project it described, so every new branch was a fresh chance to forget.
     *
     * With the pair stored together, a project switch is a value change rather than a sequence of
     * assignments somebody has to remember, and the partition job can refuse to write a doodle
     * into a project it was not computed for.
     */
    private data class LoadedDoodle(
        val projectId: String,
        val doodle: com.hereliesaz.graffitixr.common.model.Doodle?,
    )

    @Volatile private var loadedDoodle: LoadedDoodle? = null

    // doodleLoadsInFlight is declared above the init block; see the note there.

    /**
     * Restore (or clear) the wall doodle for the current project.
     *
     * **One place decides native-state ownership: the `finally` here.** Every exit path ends in it:
     *
     *  - [NativeStateOwner.LoadExit.LOADED] — [nativeStateOwner] is marked as the loader's last step (only
     *    if the project is still current).
     *  - [NativeStateOwner.LoadExit.UNCHANGED] — the idempotence early return in [applyProjectDoodle]. It
     *    used to skip the marking, so an A -> B -> A switch (the collector had dropped A's ownership
     *    on B's emission, then A came back before B's loader changed anything) left ownership null
     *    while native still held A's state, and [saveProjectWallMap] then refused every ARCore
     *    wall-map autosave for A for the rest of the session. Native still describes the project, so
     *    ownership is re-claimed — but only when no OTHER loader is mid-flight: a loader that is
     *    still rewriting native (map, paint marks, grid) must be the one to claim it when it finishes.
     *  - [NativeStateOwner.LoadExit.ABANDONED] after native was possibly touched (a throw, or cancellation
     *    with the view model) — ownership is released, since native may hold a mixture.
     *
     * Inside [applyProjectDoodle] the branches of the load decide an OUTCOME and the tail applies
     * it, with a single early return (the idempotence check). That shape is the actual fix for this
     * function's history — the wall-feature-map block used to be skipped by the refusal branch's
     * `return`, so a refused project kept the previous project's map in native and then SAVED it into
     * the refused project's file on teardown.
     */
    /**
     * A doodle saved before the capture pose was recorded (pre-Phase-2) has none, so it could never
     * be corrected or partitioned without a re-capture. Its capture view and anchor are saved with
     * the project, and the pose is exactly `cv(view) · anchor` (as MetricDoodleBuilder builds it), so
     * derive it. Only for a doodle in the display frame (not a pre-Phase-0 one) with both saved.
     */
    private fun withDerivedCaptureAnchorCam(
        doodle: com.hereliesaz.graffitixr.common.model.Doodle,
        project: com.hereliesaz.graffitixr.common.model.GraffitiProject,
    ): com.hereliesaz.graffitixr.common.model.Doodle {
        if (doodle.captureAnchorCam.size == 16 || doodle.points3d.isEmpty()) return doodle
        if (doodle.isLegacyFrame() && project.doodleCaptureRotationDeg < 0) return doodle
        val view = project.doodleViewMatrix.takeIf { it.size == 16 }?.toFloatArray() ?: return doodle
        val anchor = project.doodleAnchor.takeIf { it.size == 16 }?.toFloatArray() ?: return doodle
        val cam = RigidMath.multiply(RigidMath.glViewToCv(view), anchor)
        return doodle.copy(captureAnchorCam = cam.toList())
    }

    private fun loadDoodleIfExists() {
        viewModelScope.launch(Dispatchers.IO) {
            val project = projectRepository.currentProject.value ?: return@launch
            var exit = NativeStateOwner.LoadExit.ABANDONED
            doodleLoadsInFlight.incrementAndGet()
            try {
                applyProjectDoodle(project) { exit = it }
            } finally {
                nativeStateOwner.finishLoad(
                    project.id,
                    exit,
                    // Read AFTER the loader's work: a load overtaken by a newer project must not claim.
                    stillCurrent = projectRepository.currentProject.value?.id == project.id,
                    othersInFlight = doodleLoadsInFlight.decrementAndGet() > 0,
                )
            }
        }
    }

    /**
     * The body of [loadDoodleIfExists]. [reportExit] is called BEFORE the exit's last work (the
     * unchanged path's partition) so that a throw there still leaves the caller knowing native was
     * not touched; an exception anywhere else leaves the caller at [NativeStateOwner.LoadExit.ABANDONED].
     */
    private suspend fun applyProjectDoodle(
        project: com.hereliesaz.graffitixr.common.model.GraffitiProject,
        reportExit: (NativeStateOwner.LoadExit) -> Unit,
    ) {
        run {
            val fp = project.doodle?.let { withDerivedCaptureAnchorCam(it, project) }
            val incoming = LoadedDoodle(project.id, fp)
            val current = loadedDoodle

            // Nothing about the map changed: re-evaluate the partition (the design may have moved
            // while this emission was in flight) and touch native not at all. This guard is what
            // keeps `loadDoodleIfExists` — which runs on EVERY currentProject emission,
            // including the ones the partition itself causes — from re-pushing and, on the refusal
            // path, from re-wiping native's artwork registration every time a layer is saved.
            if (current == incoming) {
                reportExit(NativeStateOwner.LoadExit.UNCHANGED)
                partitionLiveDoodleIfNeeded()
                return
            }
            // A different project — and `current != null` is load-bearing, not defensive. On the
            // FIRST load of a session there is no previous project, and the footprint present is the
            // one the renderer just published for the artwork now on screen; nulling it there wipes
            // exactly the value the partition is about to need. (Caught by
            // `ArViewModelTest.loading an unpartitioned doodle partitions it and writes exactly
            // once`, which is why that test exists.)
            if (current != null && current.projectId != project.id) latestDesignFootprint = null
            // Native is about to be rewritten for `project`; until the END of this load it holds a
            // mixture, and no save may attribute it to this project.
            nativeStateOwner.beginSwitch(project.id)

            val intr = project.doodleIntrinsics
            val anchor = project.doodleAnchor
            // IMPLEMENTATION.md 0.7 — refuse a pre-Phase-0 metric doodle instead of
            // relocalizing against it.
            //
            // Its 3D points were back-projected with display-rotated pixels and intrinsics but a
            // SENSOR-frame view (PAPER.md §8), so they are skewed by an angle that was never
            // recorded and cannot be recovered from the file. Restoring it would put the app in the
            // exact state Phase 0 exists to end, silently, on a wall the artist has already painted
            // — and the failure would look like poor tracking rather than stale data.
            //
            // Keys on the PROJECT field, not the Doodle's, because a project saved before 0.11
            // has no rotation on either. Descriptors-only doodles are untouched: with no 3D
            // points there is no frame to be wrong about, which is what `isLegacyFrame()` encodes.
            val hasCoRegistration = intr.size >= 4 && anchor.size == 16
            val legacyFrame = fp != null && fp.isLegacyFrame() &&
                project.doodleCaptureRotationDeg < 0 && hasCoRegistration

            // IMPLEMENTATION.md 4.5 — drop the native design placement HERE, before the branches,
            // not inside them.
            //
            // The placement is `captureAnchorCam · rigidModelAnchorLocal`: a design pose expressed
            // in the frame of the doodle it was composed against. The moment a different
            // doodle is loaded, that frame is gone and the matrix describes nowhere. Only one
            // of the three branches below would have cleared it — `dropLoadedDoodle` reaches
            // `clearWallDoodle`, which does — so switching to a project with a saved metric
            // doodle left the gated corroboration search predicting every design feature at the
            // PREVIOUS project's design location, expressed in this project's frame. It fails
            // silently and in the worst possible direction: a confidently wrong place returns no
            // matches, which reads downstream as "the wall does not corroborate", pinning
            // confidence and painting progress near zero until the artist happens to drag the
            // design and trigger a re-push.
            //
            // Placed before the `when` rather than repeated in each arm because "cleared in one
            // branch and not its sibling" is the exact defect family six audits already found in
            // this function — the reason `LoadedDoodle` and the single-exit loader exist. The
            // metric branch re-pushes through `partitionLiveDoodleIfNeeded()` at the tail; the
            // descriptors-only branch has no frame to place a design in and correctly stays cleared.
            slamManager.setDesignPlacement(null, 0f, 0f)

            when {
                fp == null || legacyFrame -> {
                    if (legacyFrame) {
                        Timber.w(
                            "Refusing a pre-Phase-0 wall doodle: its 3D points are in the " +
                                "sensor frame and the capture rotation was never recorded.",
                        )
                        _uiState.update { it.copy(legacyDoodleRefused = true) }
                        // Surfaced, not merely recorded: the flag's own KDoc said it was, and until
                        // recently nothing read it, so the target silently never locked and looked
                        // like bad tracking.
                        _feedback.tryEmit(
                            com.hereliesaz.graffitixr.common.model.FeedbackEvent.Toast(
                                appContext.getString(DesignR.string.ar_feedback_legacy_doodle_geometry),
                            ),
                        )
                    }
                    // Nothing usable for this project, so nothing may be left loaded — in Kotlin or
                    // in native. Reached once per project change, not once per emission, which is
                    // what the idempotence guard above buys: `clearWallDoodle` also drops the
                    // artwork descriptors and painting progress, and running it on every emission
                    // would wipe the registration `updatePaintingGuide` established for the project
                    // currently open.
                    dropLoadedDoodle()
                }

                hasCoRegistration -> {
                    // Metric doodle: replay the true capture intrinsics + anchor so PnP reloc
                    // matches the live capture instead of using a default-intrinsics guess.
                    val view = project.doodleViewMatrix
                        .takeIf { it.size == 16 }?.toFloatArray() ?: FloatArray(0)
                    slamManager.restoreWallDoodleMetric(
                        fp.descriptorsData, fp.descriptorsRows, fp.descriptorsCols,
                        fp.descriptorsType, fp.points3d.toFloatArray(),
                        anchor.toFloatArray(), intr.toFloatArray(),
                        // Empty on projects saved before the capture view was persisted; native then
                        // skips the rectification pass rather than warping to a stale frontal frame.
                        viewMatrix = view,
                        // IMPLEMENTATION.md 2.6 — the partition travels with the points it indexes.
                        // Empty on a pre-Phase-2 project, which native reads as all-backbone, so the
                        // reload relocalizes exactly as it did before the phase landed.
                        regions = fp.regions,
                    )
                    // That view is in the world it was captured in; a reopened project is another.
                    slamManager.markDoodleViewStaleUnlessCapturedThisSession()
                    // Retained for the repartition path (2.2), with exactly the values just pushed
                    // so a later re-push cannot drift from what native holds.
                    liveDoodle = LiveDoodle(fp, anchor.toFloatArray(), intr.toFloatArray(), view)
                    // A different doodle from whatever was partitioned before, so the
                    // remembered pose describes a map that is no longer loaded.
                    lastPartitionedPose = null
                }

                else -> {
                    // Depth-path or pre-existing project: descriptors-only legacy restore.
                    slamManager.restoreWallDoodle(
                        fp.descriptorsData, fp.descriptorsRows, fp.descriptorsCols,
                        fp.descriptorsType, fp.points3d.toFloatArray(),
                    )
                    // Native holds THIS project's map, so it must not be cleared — but the map has
                    // no co-registration and cannot be partitioned, so every cache describing a
                    // partitionable doodle has to go.
                    dropPartitionState()
                }
            }

            if (fp != null && !legacyFrame) {
                // Restore the distortion-head canonical patch so the head works after reload, not
                // only in the capture session. 256x256 raw gray (= sqrt(len)); inert if absent.
                if (fp.patchData.isNotEmpty()) {
                    val side = kotlin.math.sqrt(fp.patchData.size.toDouble()).toInt()
                    if (side * side == fp.patchData.size) slamManager.setWallPatchBytes(fp.patchData, side)
                }
                // Re-publish the marks centroid (anchor-local) so the overlay re-centers on the
                // marks after reload, even though the builder doesn't run on a restored doodle.
                slamManager.overlayMarkCenterLocal =
                    if (fp.markCenterLocal.size >= 3) fp.markCenterLocal.toFloatArray() else null
                // IMPLEMENTATION.md 0.9 — the third factor of PoseFusion.composeCorrected. A
                // pre-Phase-2 doodle has none, and null there means "do not correct" rather
                // than "correct with the world anchor", which is the frame defect 0.9 fixed.
                // Also lowers the re-capture gate: this project's doodle and pose are now both in.
                slamManager.completeDoodleReplacement(
                    if (fp.captureAnchorCam.size == 16) fp.captureAnchorCam.toFloatArray() else null,
                )
            }

            loadedDoodle = incoming
            // Only after the state above is consistent, because this can launch a partition that
            // reads it.
            if (fp != null && !legacyFrame && hasCoRegistration) partitionLiveDoodleIfNeeded()
            // What the Doodle grew in earlier sessions, behind the captured rows it grew from. A
            // partition re-push of the same base keeps it (MobileGS::restoreWallDoodleMetric).
            val grown = project.grownDoodle
            if (fp != null && !legacyFrame && grown != null && grown.pointCount > 0) {
                slamManager.restoreGrownWall(grown)
            }
            lastSavedWallRows.set(slamManager.getWallKeypointCount())

            val map = project.wallFeatureMap
            if (map != null && map.pointCount > 0) {
                slamManager.restoreWallFeatureMap(map)
            } else {
                // No map on this project: clear any map left in native from a previously loaded project.
                slamManager.clearWallFeatureMap()
            }
            // Painted-design reference marks live in the doodle frame, so they are restored
            // only beside the doodle they were placed in — and cleared otherwise, so another
            // project's paint never leaks into this one's relocalization.
            val paint = project.paintMarks
            if (fp != null && !legacyFrame && paint != null && paint.pointCount > 0) {
                slamManager.restorePaintMarks(paint)
                lastSavedPaintCount.set(paint.pointCount)
            } else {
                slamManager.clearPaintMarks()
                lastSavedPaintCount.set(0)
            }
            // Area progress: its saved state, and the capture photo that supplies bare-wall colours.
            // Same condition as the paint marks — both are only meaningful in this doodle frame.
            if (fp != null && !legacyFrame) {
                project.paintGrid?.let { encoded ->
                    runCatching { java.util.Base64.getDecoder().decode(encoded) }.getOrNull()
                        ?.let { slamManager.restorePaintGrid(it) }
                }
                // The ARCore capture's own photo. Older projects predate the field: their last photo
                // is it only if no standalone capture has appended since.
                val photo = project.arCoreCaptureImageUri?.takeIf { it in project.doodleImageUris }
                    ?: project.doodleImageUris.lastOrNull()?.takeIf {
                        project.arCoreCaptureImageUri == null && project.sphereSlamReferenceUri == null
                    }
                val intr = project.doodleIntrinsics
                if (photo != null && intr.size == 4) {
                    val bmp = runCatching {
                        com.hereliesaz.graffitixr.common.util.ImageUtils.loadBitmapAsync(appContext, photo)
                    }.getOrNull()
                    if (bmp != null) slamManager.setCaptureImage(bmp, intr.toFloatArray())
                }
                // Pass B's reference, registered against the capture photo set just above.
                val clean = project.cleanWallUri?.let { uri ->
                    runCatching {
                        com.hereliesaz.graffitixr.common.util.ImageUtils.loadBitmapAsync(appContext, uri)
                    }.getOrNull()
                }
                if (clean != null) slamManager.setCleanWallImage(clean) else slamManager.clearCleanWallImage()
                _uiState.update { it.copy(hasCleanWall = clean != null) }
            } else {
                // No doodle frame to register against: the previous project's clean wall is not this one's.
                slamManager.clearCleanWallImage()
                _uiState.update { it.copy(hasCleanWall = false) }
            }
            lastSavedProgress = 0f
            // The wall's normal this project learned, for the sensor turn from the first attempt.
            slamManager.setWallNormalEnu(
                project.doodleWallNormalEnu.takeIf { it.size == 3 }?.toFloatArray(),
            )
        }
        // Last step: native now holds this project's state. The caller's `finally` claims ownership.
        reportExit(NativeStateOwner.LoadExit.LOADED)
    }

    /**
     * Seed the autosave baseline for a freshly opened project.
     *
     * This used to restore a saved ARCore point cloud too, which could not be correct — see
     * [saveMapBlocking] — and before that a native `loadModel` that had been a no-op since the
     * voxel/splat map was deleted. The wall feature map itself is restored by the project load path,
     * which re-registers it through the doodle.
     */
    private fun loadMapIfExists() {
        lastSavedMapPointCount.set(slamManager.getMapPointCount())
    }

    private fun startAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(AUTOSAVE_INTERVAL_MS)
                // Like with like: the wall feature map's live count against its count at the last save
                // (see lastSavedMapPointCount). The ARCore cloud is never persisted, so it can't say
                // whether anything persistable changed.
                if (
                    shouldAutosaveWallMap(
                        mapPoints = slamManager.getMapPointCount(),
                        savedMapPoints = lastSavedMapPointCount.get(),
                        paintMarks = slamManager.getPaintMarkCount(),
                        savedPaintMarks = lastSavedPaintCount.get(),
                        progress = slamManager.getPaintingProgress(),
                        savedProgress = lastSavedProgress,
                        wallRows = slamManager.getWallKeypointCount(),
                        savedWallRows = lastSavedWallRows.get(),
                    )
                ) {
                    saveMapNow()
                }
            }
        }
    }

    private fun stopAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = null
    }

    /**
     * Everything that belongs to the AR session's anchor, cleared when the session goes away: by
     * [exitArMode], and by [destroyArSession] (a finishing Activity, or a recreation the manifest
     * does not absorb -- font scale, locale, density -- which used to close the session yet leave the
     * UI saying an anchor existed).
     */
    private fun resetSessionAnchorState() {
        // Drop the marks-centering override so a later AR re-entry (or a different project) doesn't
        // center the overlay on a stale target's marks before a new one is built/restored.
        slamManager.overlayMarkCenterLocal = null
        // 0.9 — same lifetime: without it PoseFusion cannot compose a correction at all.
        slamManager.captureAnchorCam = null
        // Same argument, for the anchor. The ARCore session and its anchors do not survive this, so
        // "an anchor has been established" must not either — otherwise the next capture's
        // `awaitAnchorTransform` returns immediately with the previous session's pose, which is the
        // identity-shaped bug it was written to prevent, wearing different numbers.
        slamManager.clearAnchorEstablished()
        // The next session is another tracking world: the loaded doodle's capture view is not in it.
        slamManager.markDoodleViewStale()
        // The partition's in-memory state belongs to the session that built it: the retained design
        // footprint is expressed relative to an anchor that is about to stop existing.
        latestDesignFootprint = null
        dropPartitionState()
        // The UI's copy of "an anchor exists" is a separate latch, set true in
        // onPrimaryAnchorEstablished and — until this line — never set false anywhere in the
        // repository. Left standing it made the NEXT session pause splat mapping on its first
        // tracking frame (updateAutoMapping gates on it) and report scanPhase COMPLETE on a session
        // that never scanned. Sixth field to be cleared in one place and not its sibling.
        _uiState.update {
            it.copy(isAnchorEstablished = false, backboneTooSmall = false, measure = MeasureUi())
        }
        // The scan progression (visited yaw sectors, the COMPLETE latch) belongs to this session too.
        resetScanProgress()
    }

    fun destroyArSession(): Job {
        // Off-main for the same reason as exitArMode: session.close() blocks until any
        // in-flight session.update() returns, which never happens when the GL thread is
        // wedged on a camera that isn't feeding ARCore. On teardownScope, not viewModelScope:
        // this is called from onCleared and from a finishing activity's onDestroy, when
        // viewModelScope is already cancelled and a launch there never runs.
        //
        // Latch isDestroying synchronously so a renderer created before the teardown takes the
        // mutex (activity recreation) is never handed the session that is about to close.
        isDestroying = true
        renderer?.isDestroying = true
        resetSessionAnchorState()
        val ownedRenderer = renderer
        return teardownScope.launch {
            performFullCleanupLocked(ownedRenderer)
        }
    }

    // Project + page URI whose persisted hybrid page was last offered to the renderer.
    private var hybridRestoreKey: Pair<String, android.net.Uri?>? = null

    private fun hybridRestoreKey(project: com.hereliesaz.graffitixr.common.model.GraffitiProject) =
        project.id to project.hybridKpmPageUri

    // A page frozen this session, waiting for ITS doodle to commit. Guarded by [hybridLock]
    // (declared above the init block; see the note there).
    private var pendingHybridPage: HybridKpmPage? = null
    // `doodle.captureAnchorCam` as it stood when the current capture started. The pending page
    // commits only once the project's doodle has moved off this value — i.e. the capture that
    // produced the page actually produced a doodle. A capture whose doodle build fails
    // never commits its page, so a page can never be paired with a different capture's doodle.
    private var hybridKeyAtCapture: List<Float>? = null
    // Project the capture belongs to; a page never commits into a different project.
    private var hybridCaptureProjectId: String? = null

    /** Renderer: a new target capture started. Drop any uncommitted page from an earlier capture. */
    private fun onHybridCaptureStarted() {
        synchronized(hybridLock) {
            pendingHybridPage = null
            val project = projectRepository.currentProject.value
            hybridCaptureProjectId = project?.id
            hybridKeyAtCapture = project?.doodle?.captureAnchorCam ?: emptyList()
        }
    }

    /** Renderer: page↔artwork relation frozen for this session's capture. */
    private fun onHybridPageFrozen(page: HybridKpmPage) {
        synchronized(hybridLock) {
            if (hybridKeyAtCapture == null) return // no capture this session; nothing to bind to
            pendingHybridPage = page
        }
        projectRepository.currentProject.value?.let(::tryCommitPendingHybridPage)
    }

    /** Commit the pending page iff [project] now carries the doodle of the capture behind it. */
    private fun tryCommitPendingHybridPage(project: com.hereliesaz.graffitixr.common.model.GraffitiProject) {
        val page: HybridKpmPage
        val key: List<Float>
        synchronized(hybridLock) {
            val pending = pendingHybridPage ?: return
            key = HybridKpmPage.commitKeyOrNull(
                projectId = project.id,
                captureProjectId = hybridCaptureProjectId,
                doodleKey = project.doodle?.captureAnchorCam,
                keyAtCapture = hybridKeyAtCapture,
            ) ?: return // this capture's doodle has not landed (or never will)
            pendingHybridPage = null
            page = pending
        }
        persistHybridKpmPage(project.id, page, key)
    }

    /** Load [project]'s persisted hybrid KPM page off the main thread and hand it to the renderer. */
    private fun pushPersistedHybridPage(project: com.hereliesaz.graffitixr.common.model.GraffitiProject) {
        val uri = project.hybridKpmPageUri ?: return
        val r = renderer ?: return
        if (!HybridKpmPage.isBoundTo(project.doodle?.captureAnchorCam, project.hybridKpmDoodleKey)) {
            // Bound to a different (older) target, or the target was never committed: inert.
            Timber.i("ARDIAG hybrid KPM page not restored: bound to a different target")
            return
        }
        viewModelScope.launch(dispatchers.io) {
            val luma = projectManager.readHybridKpmPage(
                uri,
                project.hybridKpmPageWidthPx * project.hybridKpmPageHeightPx,
            )
            val page = HybridKpmPage.fromPersisted(
                luma = luma,
                width = project.hybridKpmPageWidthPx,
                height = project.hybridKpmPageHeightPx,
                widthMeters = project.hybridKpmPageWidthMeters,
                pageFromArtwork = project.hybridKpmPageFromArtwork,
            )
            // Only if the project and renderer are still the ones this was loaded for; the renderer
            // itself refuses it if a capture has started since.
            if (page != null && renderer === r && projectRepository.currentProject.value?.id == project.id) {
                r.restoreHybridPage(page)
            } else if (page == null) {
                Timber.w("Persisted hybrid KPM page unreadable or inconsistent; sidecar stays off")
            }
        }
    }

    /**
     * Persist a page bound to doodle [key]. Same commit discipline as the standalone reference:
     * write a new versioned file, commit metadata through an exact transform that re-checks the
     * project id AND that the doodle is still [key], then delete the superseded file — or the
     * new one if it never became authoritative.
     */
    private fun persistHybridKpmPage(projectId: String, page: HybridKpmPage, key: List<Float>) {
        viewModelScope.launch(dispatchers.io) {
            var newUri: android.net.Uri? = null
            var previousUri: android.net.Uri? = null
            try {
                newUri = projectManager.saveHybridKpmPage(appContext, projectId, page.luma)
                val candidate = newUri
                projectRepository.updateProject { current ->
                    if (current.id != projectId || current.doodle?.captureAnchorCam != key) {
                        current
                    } else {
                        previousUri = current.hybridKpmPageUri
                        current.copy(
                            hybridKpmPageUri = candidate,
                            hybridKpmPageWidthPx = page.width,
                            hybridKpmPageHeightPx = page.height,
                            hybridKpmPageWidthMeters = page.widthMeters,
                            hybridKpmPageFromArtwork = page.pageFromArtwork.toList(),
                            hybridKpmDoodleKey = key,
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not fatal and never rethrown (an uncaught throw here would crash the app): the
                // sidecar works for this session; only a reopen falls back to MobileGS.
                Timber.e(e, "Hybrid KPM page save failed")
            } finally {
                // Decide from authoritative state, not a local flag: updateProject persists before it
                // publishes, and can throw or be cancelled on either side of that.
                withContext(NonCancellable) {
                    val inMemory = newUri != null && projectRepository.currentProject.value
                        ?.takeIf { it.id == projectId }?.hybridKpmPageUri == newUri
                    val onDisk = newUri != null && runCatching {
                        projectManager.loadProjectMetadata(appContext, projectId)?.hybridKpmPageUri == newUri
                    }.getOrDefault(false)
                    when {
                        // Fully committed: the superseded file is unreferenced everywhere.
                        inMemory && previousUri != newUri ->
                            projectManager.deleteHybridKpmPage(appContext, projectId, previousUri)
                        // Neither side references the new file: it never became authoritative.
                        !inMemory && !onDisk ->
                            projectManager.deleteHybridKpmPage(appContext, projectId, newUri)
                        // Half-committed (on disk, not yet published): keep BOTH files. An orphan
                        // is harmless; deleting the previous one could leave the in-memory project —
                        // and the next save — pointing at a missing file.
                        else -> Unit
                    }
                }
            }
        }
    }

    fun attachSessionToRenderer(r: ArRenderer?) {
        // Synchronous on purpose. ArRenderer.attachSession() already guards the session read with
        // its own ReentrantLock and null-checks, and onDrawFrame swallows a torn-down session, so
        // the read-then-attach is safe. Dispatching this onto a coroutine (to "fix" a benign
        // TOCTOU) instead created a worse race: it could run AFTER performFullCleanupLocked nulled
        // `renderer`, resurrecting a reference to an already-destroyed renderer.
        renderer = r
        applyTripodSink(collaborationManager.videoWanted.value)
        renderer?.onCameraNotFeeding = { cameraEverStreamed -> onCameraNotFeeding(cameraEverStreamed) }
        // The renderer assembles the capture record on the GL thread; these hand it the two pieces
        // whose lifecycles live here. Lambdas rather than direct references so the renderer never
        // touches a SensorManager or a location client it does not own.
        renderer?.attitudeSampler = { attitudeProvider?.snapshot() }
        renderer?.locationSampler = { locationFix() }
        renderer?.savedDesignHalfWidthProvider = {
            projectRepository.currentProject.value?.arDesignHalfWidthM ?: -1f
        }
        renderer?.onDesignHalfWidthFitted = { halfW ->
            val projectId = projectRepository.currentProject.value?.id
            if (projectId != null) viewModelScope.launch(Dispatchers.IO) {
                try {
                    projectRepository.updateProject {
                        if (it.id == projectId && it.arDesignHalfWidthM <= 0f) it.copy(arDesignHalfWidthM = halfW) else it
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Timber.e(e, "Design size save failed")
                }
            }
        }
        renderer?.onHybridPageFrozen = ::onHybridPageFrozen
        renderer?.onHybridPageInvalidated = ::onHybridCaptureStarted
        // A fresh renderer has no hybrid page; offer it the persisted one.
        projectRepository.currentProject.value?.let { project ->
            if (
                shouldRestoreLocalArCoreDoodle(
                    isArCoreAvailable = _uiState.value.isArCoreAvailable,
                    coopRole = _uiState.value.coopRole,
                    peerSpatialFramePresent = _uiState.value.coopPeerSpatialFrame != null,
                )
            ) {
                hybridRestoreKey = hybridRestoreKey(project)
                pushPersistedHybridPage(project)
            }
        }
        // Re-apply the experiment switches to the FRESH renderer.
        //
        // `ArRenderer` is reconstructed whenever the AR view is (leaving and re-entering AR, and
        // some configuration changes), and a new one starts at its shipped default — `fusionEnabled
        // = false`. Without this, turning fusion on and then stepping out of AR and back would leave
        // the renderer off while `evalFusionEnabled` still reported on: the toggle would read ON, the
        // HUD would read DISABLED, and the two would disagree with no way for the artist to tell
        // which was lying. That is the same shadow-versus-mirror defect the toggle itself had, one
        // layer down, and it only became reachable once these moved out of the debug panel.
        //
        // The view model holds the INTENT; the renderer is made to match it, never the reverse.
        renderer?.fusionEnabled = _evalFusionEnabled.value
        // Same for the anchor-boundary setting: a fresh renderer starts at its default (off).
        renderer?.showAnchorBoundary = _uiState.value.showAnchorBoundary
        // Self-grow lives in the native engine rather than the renderer, so it does not reset with
        // a new ArRenderer — but re-asserting it is idempotent and costs one atomic store, and it
        // means one place re-establishes every experiment switch instead of two rules to remember.
        slamManager.setSelfGrowEnabled(_evalSelfGrowEnabled.value)
        slamManager.setChangePassEnabled(_uiState.value.changePassEnabled)
        renderer?.cvPreviewVisible = _uiState.value.cvPreviewOn
        applyFeatureMapSwitch(_evalFeatureMapEnabled.value)
        renderer?.updateAutoFocus(effectiveAutoFocus)
        // A renderer attached while a teardown is in flight must not receive the session being
        // closed; the teardown schedules a fresh session for it once the old one is gone.
        renderer?.attachSession(if (isDestroying) null else session)
    }

    fun setTrackingState(
        isTracking: Boolean,
        splatCount: Int,
        isDepthApiSupported: Boolean,
        cameraYaw: Float = 0f,
        distanceToAnchorMeters: Float = -1f,
        anchorRelativeDirection: Triple<Float, Float, Float>? = null,
        isHardwareStereo: Boolean = false,
        centerDepth: Float = -1f,
    ) {
        if (distanceToAnchorMeters > 0f) {
            val close = if (anchorIsClose) distanceToAnchorMeters < CLOSE_TARGET_EXIT_M
            else distanceToAnchorMeters < CLOSE_TARGET_ENTER_M
            if (close != anchorIsClose) {
                anchorIsClose = close
                applyAnchorFocusLock(anchorEstablished = _uiState.value.isAnchorEstablished)
            }
        }
        val progress = if (isTracking) slamManager.getPaintingProgress() else _uiState.value.paintingProgress
        // Read every tick (~15 Hz) rather than only on a successful lock — the whole point is to show
        // the FAILING states, which by definition never reach a success path.
        val relocDiag = slamManager.getRelocDiagnostics()
        // The design was registered against a Doodle of another descriptor type (re-captured with
        // another detector, or SuperPoint became (un)available): nothing it corroborates can grow
        // the Doodle or become a paint mark. Register it again for the current Doodle.
        if (relocDiag.growOutcome == com.hereliesaz.graffitixr.common.model.GrowOutcome.TYPE_MISMATCH) {
            reRegisterGuideForWallType()
        }
        // IMPLEMENTATION.md 4.6, read on the same tick and for the same reason. Separate call
        // because these are floats and the record above arrives as an int[]; the two are read one
        // after the other rather than merged natively, so they can disagree by one reloc cycle. That
        // is acceptable here — these are diagnostics, not numbers PoseFusion acts on — and it is
        // written down so a later reader does not mistake the pair for an atomic snapshot.
        val corrobDiag = slamManager.getCorroborationDiagnostics()
        // Read from the renderer rather than the engine: fusion is a Kotlin-side decision, and the
        // states that matter most (disabled, no anchor, no capture pose) are ones where PoseFusion
        // is never invoked and so has nothing to report.
        val fusionDiag = renderer?.fusionDiagnostics()
            ?: com.hereliesaz.graffitixr.common.model.FusionDiagnostics()
        val hybridDiag = renderer?.hybridKpmDiagnostics()
            ?: com.hereliesaz.graffitixr.common.model.HybridKpmDiagnostics()
        if (isInArMode) {
            renderer?.sphereSlamRuntimeStatus()?.let(::publishSphereSlamRuntimeStatus)
        }
        // Read alongside the diagnostics, not on a success path: the state this exists to expose is
        // "the capture produced nothing", which by definition never reaches one.
        val wallPoints = slamManager.getWallKeypointCount()

        val nowMs = System.currentTimeMillis()

        // Record unconditionally. The report's whole value is being able to answer "did this ever
        // happen" about a period that has already passed, which is impossible if collection has to
        // be armed first.
        diagnosticRecorder.record(relocDiag, corrobDiag, fusionDiag, wallPoints, progress, hybridDiag)
        // onTick ALWAYS, and only the emit is gated.
        //
        // The watcher goes to some trouble to advance its edge state unconditionally so that a
        // suppressed transition is dropped rather than deferred onto a later, unrelated frame.
        // Wrapping the whole call in the enable flag — as this did — reintroduced that exact defect
        // one level up: toggling the overlay off froze `prevReject` at whatever it held, and
        // toggling it back on five minutes later compared a live state against a stale one and
        // photographed the wrong moment under the right name.
        //
        // elapsedRealtime, not the wall clock beside it: the cooldowns are differences, and an NTP
        // correction backward makes `nowMs - lastAnyMs` negative — which compares below every
        // cooldown and silently refuses every capture for the rest of the session. That is the
        // failure the watcher's own KDoc narrates as fixed, and feeding it a non-monotonic clock
        // would have handed it straight back.
        val trigger = diagnosticWatcher.onTick(
            android.os.SystemClock.elapsedRealtime(), relocDiag, corrobDiag, fusionDiag,
        )
        if (trigger != null && diagnosticCaptureEnabled) {
            // tryEmit, not emit: this is the AR tick and it must not suspend. The watcher has
            // already spent a budget slot and latched any once-only trigger by this point, so a
            // dropped emit is a transition that will never be photographed and never be retried —
            // it has to be recorded, or the report's "none captured" would mean "or the request was
            // dropped", which is precisely the ambiguity the section exists to remove.
            if (!_captureRequests.tryEmit(trigger)) {
                onDiagnosticCaptureFailed(trigger, "request dropped — no collector draining")
            }
        }

        if (isTracking) { lastTrackingTimestampMs = nowMs; trackedSinceArEntry = true }
        // "Tracking failed" is for an AR start that never got going. Once the wall is anchored, a
        // long loss is the phone in a pocket, which the Doodle relocks from; it is not a failure.
        val trackingFailed = isInArMode &&
                !isTracking &&
                !_uiState.value.isAnchorEstablished &&
                arEntryTimestampMs > 0L &&
                (nowMs - lastTrackingTimestampMs) > trackingFailureGraceMs

        val sector = (((cameraYaw % 360f) + 360f) % 360f / 10f).toInt().coerceIn(0, 35)
        val sectorMask: Long
        val sectorsCovered: Int
        synchronized(visitedSectors) {
            visitedSectors[sector] = true
            sectorsCovered = visitedSectors.count { it }
            sectorMask = (0..35).fold(0L) { acc, i -> if (visitedSectors[i]) acc or (1L shl i) else acc }
        }
        val mappingProgress = sectorsCovered / 36f

        _uiState.update { state ->
            val newPhase = when (state.scanPhase) {
                ScanPhase.AMBIENT -> {
                    // The ambient 360-scan is skippable; when required it needs all 36 yaw sectors.
                    if (!state.ambientScanEnabled || sectorsCovered >= 36) {
                        ScanPhase.WALL
                    } else {
                        ScanPhase.AMBIENT
                    }
                }
                ScanPhase.WALL -> if (splatCount >= 30_000 || state.isAnchorEstablished) ScanPhase.COMPLETE else ScanPhase.WALL
                ScanPhase.COMPLETE -> ScanPhase.COMPLETE
            }
            state.copy(
                isScanning = isTracking,
                // Latches true on the first TRACKING frame — "ARCore has finished initializing".
                isArReady = state.isArReady || isTracking,
                splatCount = splatCount,
                isDepthApiSupported = isDepthApiSupported,
                paintingProgress = progress,
                featureProgress = slamManager.getFeatureProgress(),
                relocDiagnostics = relocDiag,
                corroborationDiagnostics = corrobDiag,
                fusionDiagnostics = fusionDiag,
                hybridKpmDiagnostics = hybridDiag,
                measureAvailable = renderer?.wallMeasureAvailable == true,
                wallDoodlePoints = wallPoints,
                scanPhase = newPhase,
                ambientSectorsCovered = sectorsCovered / 3, // Keep backward compatibility for 30 degree UI units if needed
                worldMappingProgress = mappingProgress,
                visitedSectorsMask = sectorMask,
                scanHint = computeScanHint(
                    isTracking = isTracking,
                    splatCount = splatCount,
                    lightLevel = state.lightLevel,
                    scanPhase = newPhase,
                    sectorsCovered = sectorsCovered / 3
                ),
                distanceToAnchorMeters = distanceToAnchorMeters,
                anchorRelativeDirection = anchorRelativeDirection,
                isHardwareStereoActive = isHardwareStereo,
                currentCenterDepth = centerDepth,
                trackingFailed = trackingFailed,
                evalLiveMetrics = if (evalLogging)
                    evalProbe.lastMetrics.copy(wallCount = slamManager.getWallKeypointCount())
                else state.evalLiveMetrics
            )
        }

        // Forced hardware-stereo that never lets VIO track in MURAL is broken on this device
        // (ARCore motion-stereo ComputeDisparity / spherical_rectifier fails). Detect it FAST —
        // well before the 5s input-timeout ANR — and reconfigure the LIVE session to mono so the
        // first AR entry recovers instead of crashing. Persisted so future sessions skip stereo too.
        // Only a start that NEVER tracked: a loss after tracking (a dark pocket, screen-off) also reads
        // zero points, and reconfiguring the session then -- and dropping stereo for good -- would
        // break the very relock the Doodle is there for.
        if (!forcedStereoUnstable &&
            isInArMode &&
            isHardwareStereo &&
            !isTracking &&
            !trackedSinceArEntry &&
            !_uiState.value.isAnchorEstablished &&
            splatCount == 0 &&
            _uiState.value.ambientScanEnabled &&
            arEntryTimestampMs > 0L &&
            (nowMs - lastTrackingTimestampMs) > STEREO_STUCK_GRACE_MS
        ) {
            recoverFromBrokenStereo()
        }

        // Battery: SLAM mapping (gaussian-splat optimization) is the dominant AR power cost. Once the
        // projection anchor is locked and tracking is solid the wall is mapped, so settle mapping;
        // tracking + relocalization keep running. It resumes automatically on tracking loss so the
        // map can re-acquire/extend.
        updateAutoMapping(isTracking)
    }

    // Last steady-state mapping-paused command we issued, so we only call into native on a change.
    // Reset to null while capture/export owns the flag, so we re-assert once that flow releases it.
    private var lastMappingPausedCmd: Boolean? = null

    /**
     * Auto-settle SLAM mapping to save battery: pause the heavy gaussian-splat mapping once the
     * projection anchor is established and tracking is solid, and resume it on tracking loss so the
     * map can re-acquire. Capture/export drive [SlamManager.setMappingPaused] directly via the
     * isCaptureRequested flow, so while a capture is requested we step aside (and forget our last
     * command, re-asserting the correct state once the capture releases the flag).
     */
    private fun updateAutoMapping(isTracking: Boolean) {
        val state = _uiState.value
        if (state.isCaptureRequested) { lastMappingPausedCmd = null; return }
        val shouldPause = isInArMode && state.isAnchorEstablished && isTracking
        if (shouldPause != lastMappingPausedCmd) {
            slamManager.setMappingPaused(shouldPause)
            lastMappingPausedCmd = shouldPause
        }
    }

    /**
     * The render watchdog reports ARCore is getting no camera frames — either update() stalled on the
     * GL thread or the camera timestamp stayed 0 for seconds. The only non-default camera config we
     * ever force is hardware-stereo, and on a device wrongly classified depth-capable that config can
     * fail to stream. Because update() stalls *before* any tracking callback fires, the callback-driven
     * detector in onTrackingUpdated (recoverFromBrokenStereo) can never run for this case. This hook is
     * driven from the side watchdog thread — alive while the GL thread is blocked — so it can still
     * self-heal: reconfigure the LIVE session to mono. No-op when we're already on mono (then the black
     * camera is some other fault and must not be mis-blamed on stereo) or when recovery already ran.
     */
    private fun onCameraNotFeeding(cameraEverStreamed: Boolean) {
        if (cameraEverStreamed) {
            // The render watchdog also fires for a GL-thread stall (slamCamera / slamFeed / mesh) that
            // happens AFTER frames were already flowing. That is a native/render stall, not a camera-feed
            // failure: it must not switch the next entry to the safe camera config or blame the camera.
            appendDiag("render stalled after camera had streamed — native/GL stall, not a camera-feed fault")
            return
        }
        if (forcedStereoUnstable || !_uiState.value.isHardwareStereoActive) {
            // Mono (either configured mono, or mono after a stereo downgrade) and no first frame ever: the
            // camera itself isn't feeding ARCore. Auto-recreating the session here is NOT safe — recreating
            // ARCore on a wedged camera crashed natively — so arm the safest-camera-config recovery and let
            // the user re-enter AR. Arming here (not only via the 10s MainScreen dead-camera watchdog) makes
            // the "exit and re-enter" the toast instructs actually recover: the next entry uses ARCore's
            // default config (no fps-variant swap, no stereo) instead of reopening the identical one.
            // Reaching this in the forcedStereoUnstable (post-downgrade) case matters because MainScreen's
            // startup watchdog has already stopped by then, so this is the only recovery left. Idempotent,
            // process-scoped, flag-only — no in-place session recreate (the restriction above still holds).
            onCameraStreamStalled()
            appendDiag("camera not feeding on mono — armed safe-config recovery; exit and re-enter AR to retry")
            _feedback.tryEmit(
                com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(
                    appContext.getString(DesignR.string.ar_feedback_camera_no_frames)
                )
            )
            return
        }
        appendDiag("self-heal: stereo not feeding -> reconfigure to mono")
        Timber.w("ARDIAG dual-lens: camera not feeding ARCore under forced stereo -> self-heal to mono")
        recoverFromBrokenStereo()
    }

    private val stereoRecoveryInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * The forced hardware-stereo path is broken on this device — switch the LIVE session to the
     * mono camera config (detach renderer → pause → set mono → resume → re-attach) so the broken
     * motion-stereo disparity stops thrashing the tracker, and persist the flag so future sessions
     * skip stereo entirely. Recoverable: the user re-enabling the ambient scan clears the flag (see
     * setAmbientScanEnabled). Runs on Dispatchers.Default — pause()/resume() block on the camera — and
     * detaches the renderer first because reconfiguring a Session the GL thread is concurrently
     * driving under a different lock is the not-thread-safe race that crashes ARCore natively.
     */
    private fun recoverFromBrokenStereo() {
        if (!stereoRecoveryInFlight.compareAndSet(false, true)) return
        forcedStereoUnstable = true
        _uiState.update { it.copy(trackingFailed = false) }
        viewModelScope.launch { settingsRepository.setForcedStereoUnstable(true) }
        viewModelScope.launch(dispatchers.default) {
            sessionMutex.withLock {
                val s = session
                if (s == null || isDestroying) return@withLock
                val wasResumed = isSessionResumed
                // Get the GL thread out of the frame body before touching the session config.
                val handoffSucceeded = renderer?.detachSessionBounded(1500L) ?: true
                if (
                    decideSessionOwnership(handoffSucceeded, SessionOwnershipAction.RECONFIGURE) ==
                    SessionOwnershipDecision.ABORT_RECONFIGURE
                ) {
                    appendDiag("stereo recovery: GL thread still owns ARCore after 1500ms — aborting live reconfigure")
                    _feedback.tryEmit(
                        com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(
                            appContext.getString(DesignR.string.ar_feedback_camera_recovery_timeout)
                        )
                    )
                    return@withLock
                }
                if (wasResumed) pauseArSessionInternal()
                try {
                    val monoConfigs = s.getSupportedCameraConfigs(CameraConfigFilter(s).apply {
                        facingDirection = CameraConfig.FacingDirection.BACK
                        targetFps = EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30)
                    })
                    if (monoConfigs.isNotEmpty()) {
                        s.cameraConfig = monoConfigs[0]
                        // A later fps-setting change reconciles from here (see reconcileLiveSessionSettings).
                        appliedCameraFps = CameraTargetFps.FPS_30
                    }
                    _uiState.update { it.copy(isHardwareStereoActive = false) }
                    Timber.w("ARDIAG dual-lens: forced stereo broken -> reconfigured LIVE session to mono")
                } catch (e: Exception) {
                    Timber.e(e, "ARDIAG stereo->mono live reconfigure failed")
                } finally {
                    // Always attempt to resume — a throw during the config swap must not leave the
                    // session permanently paused (black camera, no recovery).
                    if (isActivityResumed && isInArMode && !isDestroying) resumeArSessionInternal()
                    // Re-attach the renderer to the reconfigured session. attachSession resets the
                    // camera-streaming watchdog and frame counter, so the mono config earns a fresh
                    // CAMERA STREAMING / STALL verdict and the self-heal hook re-arms.
                    renderer?.attachSession(session)
                    // Treat the reconfigure as a fresh start so the fast detector doesn't re-fire.
                    lastTrackingTimestampMs = System.currentTimeMillis()
                }
            }
            stereoRecoveryInFlight.set(false)
        }
    }

    private val artworkRegInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * The newest design registration requested while another was in flight. It used to be dropped
     * ("the next stable design state registers once the in-flight one finishes"), but nothing ever
     * retried and the effect that calls this does not fire again for the same design, so progress and
     * paint promotion then validated against the PREVIOUS layer stack. Only the latest is kept; it is
     * run when the in-flight registration finishes. The bitmap is copied because the caller may
     * recycle its own before then.
     */
    private class PendingArtworkGuide(val bitmap: Bitmap, val key: String?, val standalone: Boolean)
    private val pendingArtworkGuide = java.util.concurrent.atomic.AtomicReference<PendingArtworkGuide?>(null)

    private fun deferArtworkGuide(bitmap: Bitmap, key: String?, standalone: Boolean) {
        val copy = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: bitmap
        pendingArtworkGuide.set(PendingArtworkGuide(copy, key, standalone))
    }

    /** Runs the deferred registration, if any, now that the in-flight one has released the flag. */
    private fun drainPendingArtworkGuide() {
        val p = pendingArtworkGuide.getAndSet(null) ?: return
        if (p.standalone) updateStandalonePaintingGuide(p.bitmap, p.key)
        else updatePaintingGuide(p.bitmap, p.key)
    }

    /**
     * Auto-fit result for the editor to apply to the AR mode adjustment: the design's move in its own
     * current local frame — [dx, dy] metres, [dThetaRad] CCW, [scale] ratio. See SlamManager.autoFitDesign.
     */
    data class DesignFit(val dx: Float, val dy: Float, val dThetaRad: Float, val scale: Float)

    private val _designFits = kotlinx.coroutines.flow.MutableSharedFlow<DesignFit>(extraBufferCapacity = 1)
    val designFits: kotlinx.coroutines.flow.SharedFlow<DesignFit> = _designFits

    // A project restarted in the app but not on the wall: after a NEW target capture or a NEW design,
    // try once to fit the design to the paint already there. Armed by those two events only, so a
    // design the artist has placed by hand is never moved out from under them.
    @Volatile private var autoFitArmed = false
    @Volatile private var lastGuideKey: String? = null

    private val alignInFlight = AtomicBoolean(false)

    /**
     * Align: fit the design to paint already on the wall (the same native match the automatic fit
     * runs), on demand. [onResult] gets the fit, or null when there is no match (notReady: nothing to
     * match yet -- no design, Doodle photo or placement -- rather than a match that failed) (bare wall, no capture
     * photo, standalone page frame, too few agreeing features, the placement moved while matching);
     * it runs on the main thread. A tap while a match (this one or the automatic fit) is running is
     * ignored: two fits from the same placement would both be applied. The
     * fit is only reported for the design that is registered: a registration in flight is waited out,
     * and a design that changed during the match voids the result. A success also disarms the
     * automatic fit, which would only repeat it.
     */
    fun alignToPaint(onResult: (fit: DesignFit?, notReady: Boolean) -> Unit) {
        if (!alignInFlight.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val settled = kotlinx.coroutines.withTimeoutOrNull(ALIGN_REGISTRATION_WAIT_MS) {
                    while (artworkRegInFlight.get() || pendingArtworkGuide.get() != null || !guideSettled()) kotlinx.coroutines.delay(50)
                    true
                } == true
                val key = lastGuideKey
                val guideAtStart = guideRequested.get()
                val r = if (settled) slamManager.autoFitDesign(minimumEvidence = true) else null   // asked for: any possible fit
                val notReady = !settled || (r == null && slamManager.lastAutoFitWasNotReady())
                val current = key == lastGuideKey && guideRequested.get() == guideAtStart && guideSettled() &&
                    !artworkRegInFlight.get() && pendingArtworkGuide.get() == null
                val fit = if (current) r?.let { DesignFit(it[0], it[1], it[2], it[3]) } else null
                if (fit != null) autoFitArmed = false
                withContext(Dispatchers.Main) { onResult(fit, notReady) }
            } finally {
                alignInFlight.set(false)
                if (autoFitRetryPending) tryAutoFit()
            }
        }
    }

    /** An automatic fit wanted while a match or a registration was running: run when that ends. */
    @Volatile private var autoFitRetryPending = false

    private fun tryAutoFit() {
        if (!autoFitArmed) return
        // A design still registering would be matched half-built (old features, no filter sets yet);
        // updatePaintingGuide calls back here once it has registered.
        if (artworkRegInFlight.get() || pendingArtworkGuide.get() != null || !guideSettled()) { autoFitRetryPending = true; return }
        // Shares Align's single flight: two matches from the same placement would both be applied.
        if (!alignInFlight.compareAndSet(false, true)) { autoFitRetryPending = true; return }
        autoFitRetryPending = false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val r = slamManager.autoFitDesign() ?: return@launch   // stays armed: retried on the next placement change
                autoFitArmed = false
                _designFits.tryEmit(DesignFit(r[0], r[1], r[2], r[3]))
                Timber.i("Auto-fit applied: ${r[4].toInt()} inliers")
            } finally {
                alignInFlight.set(false)
                if (autoFitRetryPending) tryAutoFit()
            }
        }
    }

    /**
     * Guide registrations asked for, and the last one that finished. Equal means native holds the
     * newest design asked for: a deferred one handed over to its registration is still unequal.
     */
    private val guideRequested = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var guideRegistered = 0L

    private fun guideSettled() = guideRegistered == guideRequested.get()

    /** The last design composite registered (a copy: the caller may recycle its own), for [reRegisterGuideForWallType]. */
    @Volatile private var lastGuide: Pair<Bitmap, String?>? = null
    @Volatile private var lastTypeReRegisterMs = 0L

    private fun reRegisterGuideForWallType() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTypeReRegisterMs < TYPE_REREGISTER_MIN_INTERVAL_MS) return
        val (bitmap, key) = lastGuide ?: return
        if (bitmap.isRecycled) return
        lastTypeReRegisterMs = now
        updatePaintingGuide(bitmap, key)
    }

    fun updatePaintingGuide(bitmap: Bitmap, designKey: String? = null) {
        if (lastGuide?.first !== bitmap) {
            val copy = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
            if (copy != null) lastGuide = copy to designKey
        }
        val request = guideRequested.incrementAndGet()
        if (designKey != null && designKey != lastGuideKey) {
            lastGuideKey = designKey
            autoFitArmed = true
        }
        // The design composite (design layers only — NO wall texture) is the teleological "base
        // understanding": the registered overlay the clean wall frame is validated against. Re-register
        // it as the artwork base whenever the design changes so painting-progress (and the staged
        // self-grow) track the current design. Descriptors-only here (no capture depth); the
        // 3D-dependent promotion step is staged. An overlapping registration is deferred (only the
        // newest is kept) and run when the in-flight one finishes.
        if (!artworkRegInFlight.compareAndSet(false, true)) {
            deferArtworkGuide(bitmap, designKey, standalone = false)
            return
        }
        val intr = _uiState.value.doodleCaptureIntrinsics ?: FloatArray(4)
        val view = _uiState.value.doodleCaptureViewMatrix ?: FloatArray(16)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                slamManager.setArtworkDoodle(bitmap, null, 0, 0, 0, intr, view)
            } finally {
                guideRegistered = maxOf(guideRegistered, request)
                artworkRegInFlight.set(false)
            }
            drainPendingArtworkGuide()
            tryAutoFit()
        }
    }

    @Volatile private var lastStandaloneGuideKey: String? = null
    @Volatile private var lastStandaloneNativeUiUpdateMs: Long = Long.MIN_VALUE
    /** The native Doodle generation whose standalone paint/grid/grown rows were restored. */
    @Volatile private var standaloneTeleologicalRestoredFor: Long = Long.MIN_VALUE

    /**
     * Once per standalone seed (a new native Doodle generation holding this project's fingerprint):
     * put back what the wall confirmed in earlier sessions -- grown rows, paint marks, area progress --
     * so standalone does not start from zero every session.
     */
    private fun maybeRestoreStandaloneTeleological() {
        val gen = slamManager.getDoodleGeneration()
        if (gen == standaloneTeleologicalRestoredFor) return
        val project = projectRepository.currentProject.value ?: return
        val fp = project.sphereSlamFingerprint ?: return
        // Only onto this project's own fingerprint, freshly seeded (no grown rows yet).
        if (slamManager.getWallKeypointCount() != fp.descriptorsRows) return
        standaloneTeleologicalRestoredFor = gen
        project.sphereSlamGrownDoodle?.takeIf { it.pointCount > 0 }?.let { slamManager.restoreGrownWall(it) }
        project.sphereSlamPaintMarks?.takeIf { it.pointCount > 0 }?.let { slamManager.restorePaintMarks(it) }
        project.sphereSlamPaintGrid?.let { encoded ->
            runCatching { java.util.Base64.getDecoder().decode(encoded) }.getOrNull()
                ?.let { slamManager.restorePaintGrid(it) }
        }
    }
    @Volatile private var lastStandaloneSavedMapRevision: Long = Long.MIN_VALUE
    @Volatile private var lastStandaloneMapSaveMs: Long = Long.MIN_VALUE
    private val standaloneMapSaveInFlight = AtomicBoolean(false)

    /**
     * Register the artwork validator for the ARCore-independent MobileGS path.
     *
     * This is intentionally descriptors-only: the standalone wall doodle already carries
     * metric/normalized 3D page points, while the artwork image has no depth map of its own. Native
     * MobileGS explicitly supports this mode for painting progress and falls back to global
     * clean-wall↔artwork matching until standalone design-placement gating is proven.
     *
     * Do NOT call [tryAutoFit] here. Its pose/frame assumptions are still the ARCore doodle
     * contract and are tracked separately in SPHERESLAM_TODO.md.
     */
    fun updateStandalonePaintingGuide(bitmap: Bitmap, designKey: String? = null) {
        if (designKey != null && designKey == lastStandaloneGuideKey) return
        if (!artworkRegInFlight.compareAndSet(false, true)) {
            deferArtworkGuide(bitmap, designKey, standalone = true)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                slamManager.setArtworkDoodle(
                    bitmap,
                    null,
                    0,
                    0,
                    0,
                    FloatArray(4),
                    FloatArray(16),
                )
                lastStandaloneGuideKey = designKey
                Timber.i("Standalone MobileGS artwork guide registered: key=$designKey")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.e(e, "Failed to register standalone MobileGS artwork guide")
            } finally {
                artworkRegInFlight.set(false)
            }
            drainPendingArtworkGuide()
        }
    }

    /**
     * Mirror the native MobileGS state into the backend-neutral AR UI while standalone CameraX/KPM
     * is active. The JNI getters are the same ones ARCore mode already samples in [setTrackingState].
     *
     * Called at camera cadence, but native painting progress changes at reloc cadence (~1–2 Hz), so
     * cap UI/native polling at 10 Hz to avoid pointless JNI traffic.
     */
    fun onStandaloneReferenceRegistrationChanged(registered: Boolean) {
        val wasRegistered = _uiState.value.isSphereSlamReferenceRegistered
        _uiState.update { it.copy(isSphereSlamReferenceRegistered = registered) }
        if (registered && !wasRegistered) {
            sphereFingerprintAnchorTile = currentPhotosphereTile()
            appendDiag(
                "SphereSLAM fingerprint attached to base photosphere tile=" +
                    (sphereFingerprintAnchorTile?.let { "${it.sector}:${it.band}" } ?: "unknown")
            )
        } else if (!registered && wasRegistered) {
            // Removing/replacing the precision doodle does not destroy the base SphereSLAM map.
            sphereFingerprintAnchorTile = null
        }
        val state = _uiState.value
        if (!state.isArCoreAvailabilityResolved || state.isArCoreAvailable) return
        val available = state.isSphereSlamAvailable
        publishSphereSlamRuntimeStatus(
            com.hereliesaz.graffitixr.common.model.SphereSlamRuntimeStatus(
                available = available,
                mode = if (available) {
                    com.hereliesaz.graffitixr.common.model.SphereSlamRuntimeMode.STANDALONE
                } else {
                    com.hereliesaz.graffitixr.common.model.SphereSlamRuntimeMode.UNAVAILABLE
                },
                active = available &&
                    (standalonePhotosphereActive || registered || state.sphereSlamRuntimeStatus.trackingData),
                referenceReady = registered,
                mappingData = standalonePhotosphereActive,
                trackingData = state.sphereSlamRuntimeStatus.trackingData,
            )
        )
    }

    fun onStandaloneTrackingTick(isTracking: Boolean) {
        val state = _uiState.value
        if (!state.isArCoreAvailabilityResolved || state.isArCoreAvailable) return
        val available = state.isSphereSlamAvailable
        val referenceReady = state.isSphereSlamReferenceRegistered
        publishSphereSlamRuntimeStatus(
            com.hereliesaz.graffitixr.common.model.SphereSlamRuntimeStatus(
                available = available,
                mode = if (available) {
                    com.hereliesaz.graffitixr.common.model.SphereSlamRuntimeMode.STANDALONE
                } else {
                    com.hereliesaz.graffitixr.common.model.SphereSlamRuntimeMode.UNAVAILABLE
                },
                active = available && (standalonePhotosphereActive || referenceReady || isTracking),
                referenceReady = referenceReady,
                mappingData = standalonePhotosphereActive,
                trackingData = isTracking,
            )
        )

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastStandaloneNativeUiUpdateMs < 100L) return
        lastStandaloneNativeUiUpdateMs = now

        val progress = if (isTracking) {
            slamManager.getPaintingProgress()
        } else {
            _uiState.value.paintingProgress
        }
        val featureProgress = slamManager.getFeatureProgress()
        val reloc = slamManager.getRelocDiagnostics()
        val corrob = slamManager.getCorroborationDiagnostics()
        val wallPoints = slamManager.getWallKeypointCount()
        val mapPoints = slamManager.getMapPointCount()
        val mapRevision = slamManager.getWallFeatureMapRevision()
        maybePersistStandaloneWallFeatureMap(now, mapPoints, mapRevision)
        maybeRestoreStandaloneTeleological()

        _uiState.update { state ->
            state.copy(
                isScanning = standalonePhotosphereActive || isTracking,
                isArReady = state.isArReady || standalonePhotosphereActive || isTracking,
                // Never leak stale ARCore depth/perception state into standalone. KPM normalized
                // scale is not depth, and the standalone CameraX path currently has no depth source.
                isDepthApiSupported = false,
                isHardwareStereoActive = false,
                currentCenterDepth = -1f,
                paintingProgress = progress,
                featureProgress = featureProgress,
                relocDiagnostics = reloc,
                corroborationDiagnostics = corrob,
                wallDoodlePoints = wallPoints,
            )
        }
    }

    private fun maybePersistStandaloneWallFeatureMap(
        nowMs: Long,
        mapPointCount: Int,
        mapRevision: Long,
    ) {
        if (mapPointCount <= 0 || mapRevision == lastStandaloneSavedMapRevision) return
        if (
            lastStandaloneMapSaveMs != Long.MIN_VALUE &&
            nowMs - lastStandaloneMapSaveMs < STANDALONE_MAP_SAVE_INTERVAL_MS
        ) return
        val startingProject = projectRepository.currentProject.value ?: return
        val referenceUri = startingProject.sphereSlamReferenceUri ?: return
        if (startingProject.sphereSlamFingerprint == null) return
        if (!standaloneMapSaveInFlight.compareAndSet(false, true)) return

        viewModelScope.launch(dispatchers.io) {
            try {
                val map = slamManager.getWallFeatureMap() ?: return@launch
                val mapFromFingerprint = startingProject.sphereSlamMapFromFingerprint
                if (
                    mapFromFingerprint.size != 16 ||
                    !sameStandaloneTransform(map.anchor, mapFromFingerprint)
                ) {
                    appendDiag(
                        "SphereSLAM precision-map autosave refused: " +
                            "native anchor does not match map_from_doodle",
                    )
                    return@launch
                }
                var committed = false
                projectRepository.updateProject { current ->
                    if (
                        current.id != startingProject.id ||
                        current.sphereSlamReferenceUri != referenceUri ||
                        current.sphereSlamFingerprint == null
                    ) {
                        current
                    } else {
                        committed = true
                        current.copy(
                            sphereSlamWallFeatureMap = map,
                            sphereSlamWallFeatureMapFrameVersion =
                                com.hereliesaz.graffitixr.common.model.SPHERE_SLAM_FINGERPRINT_FRAME_VERSION,
                        )
                    }
                }
                if (committed) {
                    // Revision, not point count: confidence/observation updates and cap-time
                    // replacement mutate the map even when its size is unchanged.
                    lastStandaloneSavedMapRevision = mapRevision
                    lastStandaloneMapSaveMs = android.os.SystemClock.elapsedRealtime()
                    appendDiag(
                        "SphereSLAM MobileGS map saved frame=photosphere-map " +
                            "version=" +
                            com.hereliesaz.graffitixr.common.model.SPHERE_SLAM_FINGERPRINT_FRAME_VERSION +
                            " points=" + map.pointCount,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to persist standalone SphereSLAM wall map")
            } finally {
                standaloneMapSaveInFlight.set(false)
            }
        }
    }

    fun standaloneFingerprintPlacementForConfirm(): StandaloneFingerprintPlacement? {
        val placement = pendingStandaloneFingerprintPlacement ?: return null
        return placement.copy(mapFromFingerprint = placement.mapFromFingerprint.copyOf())
    }

    private fun buildStandaloneFingerprintPlacement(
        tap: Pair<Float, Float>?,
        tapViewSize: Pair<Int, Int>?,
    ): StandaloneFingerprintPlacement? {
        // Heading, elevation and the roll of the display-upright frame the keyframes and the capture
        // tap live in — one sample, so the triple stays exact looking down at a floor.
        val attitude = latestCameraAttitude(
            com.hereliesaz.sphereslam.attitude.DeviceCameraRotation
                .bodyToDisplay(displayRotationDegrees()),
        ) ?: return null
        val rollDeg = attitude.rollDeg ?: 0f
        return synchronized(spherePhotosphereLock) {
            // The current tile's own keyframe, or (fresh tile) the session intrinsics paired with the
            // CURRENT attitude — never another tile's direction.
            val view = spherePhotosphereKeyframes.view(attitude.headingDeg, attitude.elevationDeg, rollDeg)
                ?: return@synchronized null
            // The tapped pixel's direction is rotated through heading, elevation AND roll
            // (PhotosphereMap.directionOfPixel). A missing tile range means the map is still the
            // normalized unit-radius, non-metric frame, not zero metres.
            // The tap is a fraction of the SCREEN; the view's intrinsics describe the full
            // display-upright analysis frame, of which the FILL_CENTER preview shows a centre crop.
            val frameTap = standaloneTapInFrame(tap, tapViewSize, view.width, view.height)
            val placement = com.hereliesaz.sphereslam.PhotosphereFingerprintFrame.placement(
                map = spherePhotosphere,
                view = view,
                nx = frameTap[0],
                ny = frameTap[1],
            ) ?: return@synchronized null
            StandaloneFingerprintPlacement(
                mapFromFingerprint = placement.mapFromFingerprint,
                referenceWidthUnits = placement.referenceWidthUnits,
                physicallyMetric = placement.physicallyMetric,
                tileId = placement.tileId,
            )
        }
    }

    private fun standalonePlacementBlocker(): StandalonePhotosphereCapture.Blocker? =
        StandalonePhotosphereCapture.blocker(
            attitudeSensorAvailable = cameraAttitudeProvider.isAvailable,
            attitude = latestCameraAxis,
            hasAnyKeyframe = synchronized(spherePhotosphereLock) {
                spherePhotosphereKeyframes.latest != null &&
                    spherePhotosphere.snapshot().wallHeadingDeg != null
            },
        )

    /**
     * CameraX/SphereSLAM counterpart to [onDoodleCaptured].
     *
     * Doodle capture sits above the pose backend, so standalone feeds the exact same review
     * state (raw image + removable-mark mask) instead of mounting a second capture workflow.
     * Geometry is supplied later by the standalone fingerprint builder in its canonical page frame.
     */
    fun onStandaloneTargetCaptured(bitmap: Bitmap) {
        val captureTap = pendingTapPosition
        val captureTapViewSize = pendingTapViewSize
        pendingTapPosition = null
        pendingTapViewSize = null
        pendingStandaloneFingerprintPlacement =
            buildStandaloneFingerprintPlacement(captureTap, captureTapViewSize)
        pendingStandaloneFingerprintPlacement?.let { placement ->
            sphereFingerprintAnchorTile = placement.tileId
            appendDiag(
                "SphereSLAM fingerprint capture bound to photosphere tile=" +
                    "${placement.tileId.sector}:${placement.tileId.band} " +
                    "widthUnits=${"%.4f".format(placement.referenceWidthUnits)} " +
                    "metric=${placement.physicallyMetric}"
            )
        } ?: run {
            val blocker = standalonePlacementBlocker()
            appendDiag(
                "SphereSLAM fingerprint capture has no photosphere transform" +
                    (blocker?.let { " reason=$it" } ?: "") +
                    "; confirmation will fail closed"
            )
            // Fail closed, but tell the artist what to do instead of failing silently at Confirm.
            _feedback.tryEmit(
                com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(
                    StandalonePhotosphereCapture.userText(
                        blocker ?: StandalonePhotosphereCapture.Blocker.PHOTOSPHERE_EMPTY
                    ).resolve(appContext)
                )
            )
        }
        // Stop the capture request immediately so CameraX cannot hand us a second full-resolution
        // still while the first one is being prepared for review.
        _uiState.update { it.copy(isCaptureRequested = false) }

        val initialPoints = listOf(
            Offset(0.15f, 0.15f),
            Offset(0.85f, 0.15f),
            Offset(0.85f, 0.85f),
            Offset(0.15f, 0.85f),
        )

        viewModelScope.launch(dispatchers.default) {
            val sourceWidth = bitmap.width
            val sourceHeight = bitmap.height
            val reviewBitmap = bitmap.forDoodleReview()
            try {
                val markings = reviewBitmap.isolateMarkings()
                if (reviewBitmap !== bitmap) {
                    // The CameraX JPEG can be 12+ MP. Once the bounded review/reference copy exists,
                    // keeping that original allocation only burns tens of MB and serves no runtime
                    // purpose; the standalone fingerprint/page geometry is resolution-independent.
                    bitmap.recycle()
                }
                _uiState.update {
                    it.copy(
                        doodleRawBitmap = reviewBitmap,
                        doodleDepthBuffer = null,
                        doodleDepthWidth = reviewBitmap.width,
                        doodleDepthHeight = reviewBitmap.height,
                        doodleDepthBufferWidth = 0,
                        doodleDepthBufferHeight = 0,
                        doodleDepthStride = 0,
                        doodleCaptureIntrinsics = null,
                        doodleCaptureViewMatrix = null,
                        doodleCaptureWallPlane = null,
                        tempCaptureBitmap = reviewBitmap,
                        annotatedCaptureBitmap = markings,
                        unwarpPoints = initialPoints,
                    )
                }
                appendDiag(
                    "SphereSLAM target staged for review source=${sourceWidth}x${sourceHeight} " +
                        "working=${reviewBitmap.width}x${reviewBitmap.height}"
                )
            } catch (e: Exception) {
                if (reviewBitmap !== bitmap && !reviewBitmap.isRecycled) reviewBitmap.recycle()
                if (!bitmap.isRecycled) bitmap.recycle()
                appendDiag(
                    "SphereSLAM target review failed: " +
                        (e.message ?: e.javaClass.simpleName)
                )
                _feedback.tryEmit(
                    com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(
                        appContext.getString(DesignR.string.ar_feedback_target_prepare_failed),
                        e,
                    )
                )
            }
        }
    }

    private var cvPreviewJob: kotlinx.coroutines.Job? = null

    /**
     * The CV preview: shows everything the passes are looking for, over the design, with a legend of
     * what each colour is and how much of it the wall has answered. Re-composited about once a
     * second while on, since the answers change as the artist paints.
     */
    fun toggleCvPreview() {
        val turnOn = !_uiState.value.cvPreviewOn
        cvPreviewJob?.cancel()
        cvPreviewJob = null
        _uiState.update { it.copy(cvPreviewOn = turnOn, cvPassLegend = if (turnOn) it.cvPassLegend else emptyList()) }
        if (!turnOn) {
            renderer?.cvPreviewVisible = false
            renderer?.setCvPreview(null)
            return
        }
        renderer?.cvPreviewVisible = true
        cvPreviewJob = viewModelScope.launch(dispatchers.default) {
            while (isActive) {
                val bitmap = slamManager.renderCvPreview()
                val legend = slamManager.getPassLegend()
                renderer?.setCvPreview(bitmap)
                _uiState.update { it.copy(cvPassLegend = legend) }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    // The next frame captured is the clean-wall photo, not a target (see requestCleanWallPhoto).
    @Volatile private var cleanWallPending = false
    @Volatile private var cleanWallRequestedAtMs = 0L

    /**
     * One tap: photograph the bare wall as it is now. The next captured frame becomes the clean-wall
     * photo (pass B's reference) instead of a target capture; nothing is marked or isolated.
     */
    fun requestCleanWallPhoto() {
        if (_uiState.value.coopRole == com.hereliesaz.graffitixr.common.model.CoopRole.GUEST) return
        cleanWallPending = true
        cleanWallRequestedAtMs = android.os.SystemClock.elapsedRealtime()
        requestCapture()
    }

    /**
     * The app layer has tried to save [ArUiState.cleanWallBitmap] to the project. On failure the photo
     * is not kept and the artist is told, so nothing claims a reference that will vanish on restart.
     */
    fun onCleanWallPhotoPersisted(saved: Boolean = true) {
        _uiState.update { it.copy(cleanWallBitmap = null, hasCleanWall = if (saved) it.hasCleanWall else false) }
        if (!saved) {
            slamManager.clearCleanWallImage()
            _feedback.tryEmit(
                com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(
                    appContext.getString(DesignR.string.ar_feedback_clean_wall_save_failed),
                ),
            )
        }
    }

    fun onDoodleCaptured(
        bitmap: Bitmap,
        depthBuffer: ByteBuffer?,
        colorW: Int,
        colorH: Int,
        depthBufW: Int,
        depthBufH: Int,
        depthBufStride: Int,
        intrinsics: FloatArray?,
        viewMatrix: FloatArray,
        displayRotation: Int,
        tapDistanceMeters: Float,
        wallPlane: FloatArray? = null,
        /**
         * How and where the device was held at capture. Defaulted so the doodle path and tests can
         * omit it; the real capture path always supplies one.
         */
        environment: com.hereliesaz.graffitixr.common.model.CaptureEnvironment =
            com.hereliesaz.graffitixr.common.model.CaptureEnvironment(),
    ) {
        // The latch belongs to ONE request: a capture that failed or was cancelled must not leave it up to
        // swallow the next ordinary target capture, so it only counts shortly after the tap.
        val cleanWallRequested = cleanWallPending &&
            android.os.SystemClock.elapsedRealtime() - cleanWallRequestedAtMs < CLEAN_WALL_REQUEST_WINDOW_MS
        cleanWallPending = false
        if (cleanWallRequested) {
            onCaptureRequestHandled()
            slamManager.setMappingPaused(false)
            viewModelScope.launch(dispatchers.default) {
                val upright = if (displayRotation != 0) {
                    val matrix = android.graphics.Matrix().apply { postRotate(displayRotation.toFloat()) }
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                } else bitmap
                slamManager.setCleanWallImage(upright)
                _uiState.update { it.copy(cleanWallBitmap = upright, hasCleanWall = true) }
                // Registered against the capture photo straight away only when one exists; if it could not
                // be registered, say so while the artist can still retake it.
                if (slamManager.cleanWallStatus() == SlamManager.CleanWallStatus.FAILED) {
                    _feedback.tryEmit(
                        com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(
                            appContext.getString(DesignR.string.ar_feedback_clean_wall_register_failed),
                        ),
                    )
                }
            }
            return
        }
        autoFitArmed = true // a fresh photo of the wall may show paint the new design should fit
        // Doodle demo: a headless capture (no tap, no review) — build the doodle from the
        // drawing and return before the normal capture/review flow runs. Clear the capture-request
        // flag ourselves (the normal paths below do this) so the renderer isn't re-armed every frame.
        if (doodlePhaseActive && !doodleBuilt) {
            onCaptureRequestHandled()
            buildDoodle(bitmap, intrinsics, viewMatrix, displayRotation)
            return
        }

        val tapPos = pendingTapPosition
        val extent = computePhysicalExtent(
            depthBuffer, depthBufW, depthBufH, colorW, colorH, intrinsics, depthBufStride, displayRotation,
        )
        if (tapPos != null) pendingTapPosition = null
        // Stop the capture request NOW, on this (GL) thread's call: the renderer re-arms a capture
        // every frame while the flag is up, and the preparation below no longer happens inline.
        _uiState.update { it.copy(isCaptureRequested = false) }

        if (tapPos == null) {
            extent?.let { (halfW, halfH) ->
                renderer?.updateOverlayExtent(halfW, halfH)
            }
        }

        // The rotation (a full-frame Bitmap copy) and isolateMarkings (an adaptive threshold over
        // every pixel plus a flood fill) used to run right here, inside `_uiState.update {}` — on the
        // GL thread, which also re-ran them whenever the update lambda was retried under CAS
        // contention. Compute off the caller's thread and publish ONE state with the finished result.
        // A newer capture, or Retake/cancel, supersedes this one: the job is cancelled and a stale
        // result is never written.
        captureComputeJob?.cancel()
        captureComputeJob = viewModelScope.launch(dispatchers.default) {
          try {
            val rotatedBmp = if (displayRotation != 0) {
                val matrix = android.graphics.Matrix().apply { postRotate(displayRotation.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else bitmap
            val markings = rotatedBmp.isolateMarkings()
            ensureActive()

            if (tapPos != null) {
                // INITIALIZE: Default unwarp quad to the corners of the view (NORMALIZED)
                val padding = 0.15f
                val initialPoints = listOf(
                    Offset(padding, padding),
                    Offset(1f - padding, padding),
                    Offset(1f - padding, 1f - padding),
                    Offset(padding, 1f - padding)
                )
                _uiState.update {
                    it.copy(
                        tapMarks = it.tapMarks + com.hereliesaz.graffitixr.common.model.TapMark(tapPos.first, tapPos.second, tapDistanceMeters),
                        doodleRawBitmap = bitmap,
                        doodleDisplayRotation = displayRotation,
                        doodleCaptureEnvironment = environment,
                        doodleDepthBuffer = depthBuffer,
                        doodleDepthWidth = colorW,
                        doodleDepthHeight = colorH,
                        doodleDepthBufferWidth = depthBufW,
                        doodleDepthBufferHeight = depthBufH,
                        doodleDepthStride = depthBufStride,
                        doodleCaptureIntrinsics = intrinsics,
                        doodleCaptureViewMatrix = viewMatrix,
                        doodleCaptureWallPlane = wallPlane,
                        isCaptureRequested = false,
                        tempCaptureBitmap = rotatedBmp,
                        annotatedCaptureBitmap = markings,
                        unwarpPoints = initialPoints
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        tempCaptureBitmap = rotatedBmp,
                        annotatedCaptureBitmap = markings,
                        doodleRawBitmap = bitmap,
                        doodleDisplayRotation = displayRotation,
                        doodleCaptureEnvironment = environment,
                        doodleDepthBuffer = depthBuffer,
                        doodleDepthWidth = colorW,
                        doodleDepthHeight = colorH,
                        doodleDepthBufferWidth = depthBufW,
                        doodleDepthBufferHeight = depthBufH,
                        doodleDepthStride = depthBufStride,
                        doodleCaptureIntrinsics = intrinsics,
                        doodleCaptureViewMatrix = viewMatrix,
                        doodleCaptureWallPlane = wallPlane,
                        isCaptureRequested = false
                    )
                }
            }
          } catch (e: Exception) {
              if (e is kotlinx.coroutines.CancellationException) throw e
              onCaptureFailed(e)
          }
        }
    }

    /**
     * A target capture failed (the renderer threw before reaching [onDoodleCaptured], or preparing
     * the frame did). Drops the request so the capture button / tap re-arms (a request left up
     * blocks every later tap), logs it, and tells the artist to try again. Safe from any thread.
     */
    fun onCaptureFailed(e: Throwable) {
        pendingTapPosition = null
        onCaptureRequestHandled()
        appendDiag("Target capture failed: ${e.javaClass.simpleName}: ${e.message}")
        _feedback.tryEmit(
            com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(
                appContext.getString(DesignR.string.ar_feedback_capture_failed), e
            )
        )
    }

    // The in-flight rotate + isolateMarkings of the latest capture (see onDoodleCaptured).
    private var captureComputeJob: Job? = null

    /**
     * A capture is being discarded (Retake, cancel, confirm): stop its preparation, drop queued mark
     * erases and release the eraser's pixel buffers, so none of it lands on the NEXT capture's mask.
     */
    private fun resetCapturePipeline() {
        cleanWallPending = false
        captureComputeJob?.cancel()
        captureComputeJob = null
        // FIRST: from here every sample queued for the discarded capture (including a batch a drain
        // has already polled and is about to apply) fails the generation check in
        // applyPendingMarkRemovalsLocked, so it cannot erase whatever mask comes next.
        captureGeneration.incrementAndGet()
        pendingMarkRemovals.clear()
        // The eraser is mutex-guarded state: drop it under the mutex rather than racing a drain that
        // may be assigning it. (A stale eraser could not be applied to the new mask anyway - it is
        // keyed by bitmap identity - this releases its pixel buffers without a data race.)
        viewModelScope.launch(dispatchers.default) {
            markRemovalMutex.withLock { markEraser = null }
        }
    }

    /**
     * Remove the ENTIRE mark the user tapped or dragged onto from the doodle mask.
     * Floods the whole connected highlighted blob to transparent (see [MarkEraser]) — no partial
     * pixel erasing, no per-point dots. A drag calls this per touch sample. Samples are queued and
     * applied in batches to ONE working pixel buffer, publishing one new mask per batch; a touch with
     * no mark in reach erases nothing and publishes nothing (it costs a bounded neighbourhood scan,
     * not an image copy). The mask's alpha channel is what the native doodle masker reads, so
     * the mark simply stops contributing.
     *
     * Asynchronous: the mask in [uiState] lags the touches by the queue. Anything that consumes the
     * mask (Confirm) must go through [awaitLatestDoodleCapture], which applies every queued erase first.
     */
    // Serializes the batches so each starts from the LATEST mask rather than a snapshot taken when the
    // gesture sample fired. Without this, two rapid removals both read the same bitmap and the later
    // write clobbers the earlier one, making erased marks reappear. Samples are enqueued
    // synchronously, BEFORE the drain is launched, so a drain (or a flush) that holds this mutex
    // always sees every sample enqueued before it started.
    private val markRemovalMutex = Mutex()
    /** One queued erase sample, tagged with the capture it was aimed at. */
    private class MarkRemoval(val generation: Int, val nx: Float, val ny: Float)

    private val pendingMarkRemovals = java.util.concurrent.ConcurrentLinkedQueue<MarkRemoval>()

    // Identity of the capture whose mask erases apply to; advanced by resetCapturePipeline. A sample
    // is applied only if the generation it was queued under is still this one when the drain has the
    // current mask in hand - otherwise a drag sample that was already polled (or arrived late) would
    // erase the NEW capture's mask.
    private val captureGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    /** Test seam, null in production: runs in a drain after the queue is polled, before the mask is read. */
    @Volatile internal var markBatchPolledHookForTest: (() -> Unit)? = null
    // Pixel buffers of the mask currently on screen (see MarkEraser). Guarded by markRemovalMutex.
    @Volatile private var markEraser: MarkEraser? = null

    fun removeMarkAt(nx: Float, ny: Float) {
        pendingMarkRemovals.add(MarkRemoval(captureGeneration.get(), nx, ny))
        viewModelScope.launch(dispatchers.default) {
            markRemovalMutex.withLock { applyPendingMarkRemovalsLocked() }
        }
    }

    /** Apply every queued erase to the current mask and publish it once. Caller holds [markRemovalMutex]. */
    private fun applyPendingMarkRemovalsLocked() {
        val polled = ArrayList<MarkRemoval>()
        while (true) polled.add(pendingMarkRemovals.poll() ?: break)
        if (polled.isEmpty()) return
        markBatchPolledHookForTest?.invoke()
        val current = _uiState.value.annotatedCaptureBitmap ?: return
        // Read AFTER the mask: a capture reset bumps the generation before any later mask is
        // published, so if `current` is a newer capture's mask the bump is already visible here.
        val generation = captureGeneration.get()
        val batch = polled.filter { it.generation == generation }
        if (batch.isEmpty()) return
        val eraser = markEraser?.takeIf { it.isFor(current) } ?: MarkEraser(current).also { markEraser = it }
        var changed = false
        for (sample in batch) if (eraser.erase(sample.nx, sample.ny)) changed = true
        if (!changed) return
        val updated = eraser.publish()
        // Only if the mask is still the one these erases were applied to: a Retake or new capture
        // during the batch replaced it, and the stale result must not overwrite the new mask.
        _uiState.update { if (it.annotatedCaptureBitmap === current) it.copy(annotatedCaptureBitmap = updated) else it }
    }

    /**
     * The capture bitmap and doodle mask as they stand NOW, after every mark erase queued so far
     * has been applied. Confirm calls this instead of reading the composition's snapshot, which can
     * predate erases still in flight — the artist taps the last mark and Confirm immediately, and the
     * doodle was built with that mark still in it.
     */
    suspend fun awaitLatestDoodleCapture(): Pair<Bitmap?, Bitmap?> {
        withContext(dispatchers.default) {
            markRemovalMutex.withLock { applyPendingMarkRemovalsLocked() }
        }
        val state = _uiState.value
        return state.tempCaptureBitmap to state.annotatedCaptureBitmap
    }

    // ── AR Measure (BACKLOG Phase 6 step 1) ─────────────────────────────────────────────────────
    // Points of the in-progress measurement in the unfused anchor frame, parallel to MeasureUi.points.
    // Main-thread only (tap handler, its viewModelScope continuation, rail clicks).
    private val measureLocalPoints = mutableListOf<FloatArray>()
    // The first tap's wall plane (anchor-local); the second tap is intersected with it.
    private var measurePlaneLocal: FloatArray? = null
    private var measureJob: kotlinx.coroutines.Job? = null
    private var measureSaveJob: kotlinx.coroutines.Job? = null
    // Bumped whenever the on-screen reading is discarded (start/Redo/cancel); a save checks it inside
    // the persistence transform so a stale width can't be written after a newer one.
    private val measureGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    /** Toggle Measure. Starting requires an established anchor (the wall frame to measure in). */
    fun toggleMeasure() {
        if (_uiState.value.measure.active) {
            cancelMeasure()
            return
        }
        if (!_uiState.value.isAnchorEstablished) return
        measureJob?.cancel()
        measureSaveJob?.cancel()
        measureGeneration.incrementAndGet()
        measureLocalPoints.clear()
        measurePlaneLocal = null
        _uiState.update { it.copy(measure = MeasureUi(active = true)) }
    }

    fun cancelMeasure() {
        measureJob?.cancel()
        measureSaveJob?.cancel()
        measureGeneration.incrementAndGet()
        measureLocalPoints.clear()
        measurePlaneLocal = null
        _uiState.update { it.copy(measure = MeasureUi()) }
    }

    /** Start over without leaving Measure (the "Redo" action). */
    fun redoMeasure() {
        measureJob?.cancel()
        measureSaveJob?.cancel()
        measureGeneration.incrementAndGet()
        measureLocalPoints.clear()
        measurePlaneLocal = null
        _uiState.update { it.copy(measure = MeasureUi(active = true)) }
    }

    /** A screen tap while Measure is active. Ignored once two points are in or one is resolving. */
    fun onMeasureTap(nx: Float, ny: Float) {
        val state = _uiState.value.measure
        if (!state.active || state.points.size >= 2 || measureJob?.isActive == true) return
        val r = renderer ?: return
        measureJob = viewModelScope.launch {
            val point = kotlinx.coroutines.withTimeoutOrNull(MEASURE_TAP_TIMEOUT_MS) {
                r.requestWallPoint(nx, ny, measurePlaneLocal).await()
            }
            if (!_uiState.value.measure.active) return@launch
            if (point == null) {
                _uiState.update { it.copy(measure = it.measure.copy(failed = true)) }
                return@launch
            }
            if (measureLocalPoints.isEmpty()) measurePlaneLocal = point.planeLocal
            measureLocalPoints += point.local
            val points = _uiState.value.measure.points + TapMark(nx, ny, -1f)
            val width = if (measureLocalPoints.size == 2) {
                com.hereliesaz.graffitixr.feature.ar.anchor.WallMeasure
                    .widthMeters(measureLocalPoints[0], measureLocalPoints[1])
            } else {
                null
            }
            if (measureLocalPoints.size == 2 && width == null) {
                // Out of the plausible range (too close together, or beyond 100 m): keep the first
                // point and ask for the second again.
                measureLocalPoints.removeAt(1)
                _uiState.update { it.copy(measure = it.measure.copy(failed = true)) }
                return@launch
            }
            _uiState.update {
                it.copy(measure = it.measure.copy(points = points, resultMeters = width, failed = false))
            }
        }
    }

    /**
     * Persist the finished measurement as the project's wall width, and leave Measure only once it
     * is saved. On failure (write error, or the project changed underneath) the reading stays on
     * screen and the artist is told, so it can be saved again rather than silently lost.
     *
     * In co-op the saved width is also published as [com.hereliesaz.graffitixr.common.model.Op.WallWidth],
     * for either role: a host's goes to its guests; a guest's (the usual case — the guest is the one
     * walking the wall) goes to the host, which persists it to the real project and rebroadcasts it.
     * Nothing here depends on the device or input the artist used, so a glasses-driven session
     * behaves the same.
     */
    fun saveMeasure() {
        val width = _uiState.value.measure.resultMeters ?: return
        val projectId = projectRepository.currentProject.value?.id ?: return
        val generation = measureGeneration.get()
        measureSaveJob?.cancel()
        measureSaveJob = viewModelScope.launch {
            val saved = withContext(dispatchers.io) {
                com.hereliesaz.graffitixr.feature.ar.anchor.WallWidthPersistence
                    .save(projectRepository, projectId, width) { measureGeneration.get() == generation }
            }
            if (measureGeneration.get() != generation) return@launch // Redo/cancel superseded it
            if (saved) {
                // Outside a session this has nowhere to go and is dropped by the manager.
                collaborationManager.submitOp(com.hereliesaz.graffitixr.common.model.Op.WallWidth(width))
                cancelMeasure()
            } else {
                _feedback.tryEmit(
                    com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(appContext.getString(DesignR.string.ar_feedback_wall_width_save_failed)),
                )
            }
        }
    }

    /**
     * A target-capture tap at ([nx], [ny]) as fractions of the camera view. [viewWidthPx] /
     * [viewHeightPx] are that view's size in pixels: ARCore maps view taps itself (hit test,
     * `transformCoordinates2d`), but the standalone path must undo CameraPreview's FILL_CENTER crop
     * to find the tapped pixel in the full frame. Omitted (0), the tap is taken as frame-relative.
     */
    fun onScreenTap(nx: Float, ny: Float, viewWidthPx: Int = 0, viewHeightPx: Int = 0) {
        // A co-op guest keeps the session's spatial frame: re-targeting re-anchors only this device
        // and no Op carries it, so it would split the peers' frames.
        if (_uiState.value.coopRole == com.hereliesaz.graffitixr.common.model.CoopRole.GUEST) return
        pendingTapPosition = nx to ny
        pendingTapViewSize = if (viewWidthPx > 0 && viewHeightPx > 0) viewWidthPx to viewHeightPx else null
        // Hand the tap to the renderer so it can measure the camera→point distance at that pixel
        // (and add a fusion support anchor) on the GL thread during capture.
        renderer?.pendingCaptureTap = floatArrayOf(nx, ny)
        requestCapture()
    }

    fun clearTapHighlights() {
        pendingTapPosition = null
        pendingStandaloneFingerprintPlacement = null
        _uiState.update {
            it.copy(
                tapMarks = emptyList(),
                unwarpPoints = emptyList()
            )
        }
    }

    /**
     * Discard a just-captured frame so the artist can re-aim and re-tap without restarting capture —
     * used when single-capture target creation is refused because the tap wasn't on a green wall
     * plane. Nulls the captured bitmap (so the capture→review effect won't re-fire on stale data) and
     * the captured wall plane, and clears the on-image markers.
     */
    fun clearCaptureForRetry() {
        slamManager.abortDoodleReplacement()
        pendingTapPosition = null
        pendingStandaloneFingerprintPlacement = null
        resetCapturePipeline()
        _uiState.update {
            it.copy(
                tempCaptureBitmap = null,
                annotatedCaptureBitmap = null,
                doodleCaptureWallPlane = null,
                tapMarks = emptyList(),
                unwarpPoints = emptyList()
            )
        }
    }

    /**
     * Half-extents of the anchor-region border, from the centre depth sample.
     *
     * [colorW]/[colorH] are the RAW sensor-frame image dimensions, while [intrinsics] arrive already
     * rotated by [rotationDeg] (CaptureRotation.rotateIntrinsics swaps fx/fy on the quarter turns).
     * Each dimension must be divided by the focal length of the SAME frame, so the quarter turns
     * read the sensor focals back out of the swapped slots.
     */
    fun computePhysicalExtent(
        depthBuffer: ByteBuffer?,
        depthW: Int, depthH: Int,
        colorW: Int, colorH: Int,
        intrinsics: FloatArray?,
        stride: Int,
        rotationDeg: Int = 0,
    ): Pair<Float, Float>? {
        if (depthBuffer == null || intrinsics == null || intrinsics.size < 2) return null

        // Read through DepthLookup rather than getShort()-ing the buffer here: it applies the
        // little-endian byte order DEPTH16 requires (a raw ByteBuffer defaults to BIG_ENDIAN, which
        // silently byte-swapped every sample) and rejects out-of-range samples, not just zeros.
        val depthM = com.hereliesaz.graffitixr.feature.ar.eval.DepthLookup.depthMetersAt(
            depthBuffer, stride, depthW, depthH, u = 0.5f, v = 0.5f
        )
        if (depthM <= 0f) return null

        val quarterTurn = (((rotationDeg % 360) + 360) % 360).let { it == 90 || it == 270 }
        val sensorFx = if (quarterTurn) intrinsics[1] else intrinsics[0]
        val sensorFy = if (quarterTurn) intrinsics[0] else intrinsics[1]
        if (sensorFx <= 0f || sensorFy <= 0f) return null

        // Full frame footprint at the centre depth, scaled down to the border's share of it.
        val halfW = (depthM * (colorW / 2f) / sensorFx) * ANCHOR_BORDER_FRAME_FRACTION
        val halfH = (depthM * (colorH / 2f) / sensorFy) * ANCHOR_BORDER_FRAME_FRACTION

        return halfW to halfH
    }


    fun onCaptureConsumed() {
        clearCaptureState()
        slamManager.setMappingPaused(false)
    }

    fun setInitialAnchorFromCapture() {
        // Snapshot BEFORE raising the flag, and publish it, because this is the only point at which
        // "the generation before the anchor this capture asked for" is knowable. A caller that takes
        // its own snapshot afterwards races the GL thread: if establishment lands first, the
        // snapshot is already the NEW generation, the wait can never be satisfied, and a capture
        // that succeeded is discarded with "couldn't lock an anchor".
        slamManager.captureAnchorGenerationBaseline = slamManager.anchorGeneration.value
        slamManager.captureSessionEpochBaseline = slamManager.sessionEpochValue
        // A (re-)capture replaces the anchor, and `captureAnchorCam` is expressed against the anchor
        // it was built for. Left in place it would pair the NEW anchor with the OLD capture pose from
        // the moment the renderer resets fusion until the builder publishes the new one (it awaits
        // this anchor first, so the new value always lands after this clear). If the build then fails,
        // fusion reports NO_CAPTURE_POSE instead of correcting in the wrong frame.
        //
        // Beyond the pose, native still holds the PREVIOUS wall's doodle and keeps solving against
        // it, so its results (seq advancing) are in the old wall's frame too. The replacement gate
        // makes the renderer ignore reloc and the arbiter never pick it until the builder (or a
        // project load) installs the new doodle AND the new pose.
        slamManager.beginDoodleReplacement()
        // Anchor on the wall plane the artist tapped, not wherever the centre hit test happens to land.
        renderer?.pendingCapturedWallPlane = _uiState.value.doodleCaptureWallPlane?.copyOf()
        renderer?.pendingAnchorEstablishment = true
    }

    fun requestCapture() {
        slamManager.setMappingPaused(true)
        _uiState.update { it.copy(isCaptureRequested = true) }
    }

    /**
     * Asks the renderer to read back its composited framebuffer, delivering it to [callback] on the
     * main thread. Returns false when no renderer is attached — AR mode without camera permission,
     * or before/after the GLSurfaceView's lifetime — in which case nothing was requested and the
     * caller must fall back. Previously both statements were null-safe no-ops, so Export in AR mode
     * with no renderer did nothing at all and reported nothing.
     */
    fun requestExport(callback: (Bitmap) -> Unit): Boolean {
        val r = renderer ?: return false
        // Suppress the perception layers for the readback here rather than relying on the
        // `hideVisualization = isExporting` push in MainScreen's AndroidView update block: that only
        // runs on recomposition, so the GL thread could reach the readback first. Restore whatever the
        // flag was (anchorEstablished drives it independently) once the frame is captured.
        val previousHide = r.hideVisualization
        r.hideVisualization = true
        r.onExportCaptured = { bmp ->
            r.hideVisualization = previousHide
            viewModelScope.launch(Dispatchers.Main) {
                callback(bmp)
            }
        }
        r.exportRequested = true
        return true
    }

    fun onCaptureRequestHandled() {
        _uiState.update { it.copy(isCaptureRequested = false) }
    }

    fun setUnwarpPoints(points: List<Offset>) {
        _uiState.update { it.copy(unwarpPoints = points) }
    }

    fun toggleFlashlight() {
        _uiState.update { it.copy(isFlashlightOn = !it.isFlashlightOn) }
    }

    /**
     * ARCore refused a torch request (no flash unit, or a camera config that can't drive one).
     * Called from the GL thread. Drop the toggle back off so the rail button reflects the real
     * state instead of latching on over a light that never came on.
     */
    fun onFlashlightUnavailable() {
        _uiState.update { it.copy(isFlashlightOn = false) }
        appendDiag("flashlight: unavailable on this device/camera config")
    }

    fun updateLightLevel(level: Float) {
        _uiState.update { it.copy(lightLevel = level) }
    }

    /**
     * Called from ArRenderer (on the GL thread) the first time the primary
     * anchor is created for this session. Flipping isAnchorEstablished is
     * what unlocks the Design rail (canEdit) and advances scanPhase to
     * COMPLETE in the next tracking-update tick. _uiState is a
     * MutableStateFlow so update() is safe from any thread.
     */
    fun onPrimaryAnchorEstablished() {
        _uiState.update { it.copy(isAnchorEstablished = true) }
        // Anchor placed — lock focus to FIXED so the focal length stops sweeping during tracking.
        applyAnchorFocusLock(anchorEstablished = true)
    }

    /**
     * Fired once by the renderer (GL thread) when the first tracking plane appears. Latches
     * [ArUiState.planeDetected] so the onboarding overlay can drop the "move your device" guidance.
     * MutableStateFlow.update is thread-safe, matching onPrimaryAnchorEstablished above.
     */
    fun onFirstPlaneDetected() {
        _uiState.update { if (it.planeDetected) it else it.copy(planeDetected = true) }
    }

    /** Doodle demo (GL thread): the overlay has held one spot long enough — trigger the artwork swap. */
    fun onDoodleLocked() {
        _uiState.update { if (it.doodleLocked) it else it.copy(doodleLocked = true) }
    }

    // --- Doodle demo: headless doodle build ---
    internal companion object {
        /** An anchor nearer than this is a close target (canvas, paper): focus stays AUTO. */
        const val CLOSE_TARGET_ENTER_M = 1.0f
        /** A close target becomes far again only beyond this, so focus doesn't flip at the edge. */
        const val CLOSE_TARGET_EXIT_M = 1.3f

        /** Tripod stream (Link): longest edge and JPEG quality of each streamed frame. */
        const val TRIPOD_MAX_EDGE_PX = 960
        const val TRIPOD_JPEG_QUALITY = 70

        /** A clean-wall photo request counts only for the capture that follows it this closely. */
        private const val CLEAN_WALL_REQUEST_WINDOW_MS = 5_000L
        private const val MIB = 1024L * 1024L
        /** A failing wall-map / progress save is reported at most this often. */
        private const val PROGRESS_SAVE_FAILURE_NOTICE_MS = 60_000L
        /** cos 2°: a learned wall normal is saved again only once it has moved more than this. */
        private const val WALL_NORMAL_RESAVE_COS = 0.99939f
        /** How long a Measure tap waits for the GL thread to resolve it. */
        private const val MEASURE_TAP_TIMEOUT_MS = 1_000L
        /** Quiet period after the last glow-intensity slider tick before it is written to DataStore. */
        private const val COVERAGE_GLOW_PERSIST_DEBOUNCE_MS = 250L
        private const val OPAQUE_ALPHA = 0xFF000000.toInt()
        /** How often the AR session checks whether the map has grown enough to be worth rewriting. */
        const val AUTOSAVE_INTERVAL_MS = 30_000L

        /**
         * Fraction of the camera's full frame footprint (at the centre depth) that the anchor-region
         * border spans, per axis. No derivation or measurement behind it is recorded: it is an
         * empirical first-pass value chosen so the border marks a modest region around the aim point
         * rather than the whole view. It only sizes that visual border — the artwork quad itself is
         * always [com.hereliesaz.graffitixr.feature.ar.rendering.OverlayRenderer.QUAD_HALF_EXTENT].
         */
        const val ANCHOR_BORDER_FRAME_FRACTION = 0.18f

        /** How often the design may be re-registered for a Doodle of another descriptor type. */
        const val TYPE_REREGISTER_MIN_INTERVAL_MS = 10_000L

        /** Longest Align waits for a design registration in flight before giving up as "no match". */
        const val ALIGN_REGISTRATION_WAIT_MS = 5_000L

        /**
         * How many new cloud points must accumulate since the last save before the autosave rewrites.
         *
         * A threshold rather than "any growth" because the save serialises the whole cloud and merges
         * the project record; doing that on every tick of a slowly-drifting count is churn. 500 is
         * roughly a few seconds of active scanning, so at most that much work is at risk.
         */
        const val AUTOSAVE_POINT_DELTA = 500

        /** New painted-design reference marks since the last save that trigger an autosave. */
        const val AUTOSAVE_PAINT_DELTA = 20

        /** Area-progress gain since the last save that triggers an autosave. */
        const val AUTOSAVE_PROGRESS_DELTA = 0.02f

        /**
         * Fixed RANSAC seed for eval runs (`IMPLEMENTATION.md` 6a.4, `EVALUATION.md` §3.1).
         *
         * The value is arbitrary; what matters is that it is a CONSTANT and that it is recorded in
         * the run-identity sidecar, so two runs being compared drew the same RANSAC sample sequence
         * and a reader can tell which sequence that was. Changing it invalidates comparisons against
         * previously recorded runs, so treat it as part of the run identity rather than a knob.
         */
        const val EVAL_RNG_SEED = 20260801L

        /**
         * How many eval CSV/sidecar files the diagnostic bundle attaches, newest first.
         *
         * Bounded because `eval/` accumulates one pair per logged run for the life of the install,
         * and a share sheet handed forty attachments is a share sheet nobody sends. Two runs is one
         * A/B, which is the comparison the bundle is usually for.
         */
        const val MAX_BUNDLED_EVAL_FILES = 4

        const val DOODLE_CAPTURE_INTERVAL_MS = 2500L
        // After this long without a relocalization lock (featureless wall), place on the plain anchor.
        // Measured from the point an anchor + wall plane exist, i.e. from when detection can start.
        const val DOODLE_LOCK_TIMEOUT_MS = 30_000L
        // Hard cap on the whole detect phase, measured from when it starts. Guarantees the phase ends
        // even if ARCore never finds a surface, so onboarding can't run indefinitely.
        const val DOODLE_PHASE_TIMEOUT_MS = 60_000L

        /**
         * Bounded retries for [SessionLifecycleOutcome.LockTimeout] from [ArRenderer.requestResume] /
         * [ArRenderer.requestPause]. A timeout there means the GL thread was still inside
         * `onDrawFrame` holding `sessionLock` when the bounded wait expired — usually a single slow
         * frame (a heavy perception redraw, a GC pause), not a permanently wedged thread. A few short
         * retries give that frame a chance to finish and release the lock before we give up and leave
         * the user staring at a dead camera; giving up after retries still leaves them informed via
         * [_feedback] rather than silently stuck.
         */
        private const val STANDALONE_MAP_SAVE_INTERVAL_MS = 2_000L
        const val LOCK_TIMEOUT_MAX_RETRIES = 3
        const val LOCK_TIMEOUT_RETRY_DELAY_MS = 200L
    }
    // @Volatile: written on main (setDoodlePhase) / IO (build result) and read on the GL thread
    // (onDoodleCaptured), so the GL thread must observe the latest value.
    @Volatile private var doodlePhaseActive = false
    @Volatile private var doodleBuilt = false
    // Guards against re-entrant builds if captures arrive faster than a build completes.
    @Volatile private var doodleBuildInFlight = false
    private var doodleCaptureJob: Job? = null

    /**
     * Host tells us the first-run DETECT stage is running — the user has already drawn the scribble on
     * their wall and is now pointing the camera at it. While active we periodically capture the live
     * frame (no tap) and try to build a wall doodle from the marks; the builder returns null
     * until there is enough texture, so this self-times to "the drawing is visible enough".
     *
     * Completion is the doodle landing, not the user holding still: a built doodle means
     * the marks were genuinely recognised and relocalization has something to track, which is exactly
     * what "detected, ready to go" should mean. The renderer's stability lock remains as a second
     * trigger, and the timeout below as a last resort.
     */
    fun setDoodlePhase(active: Boolean) {
        if (doodlePhaseActive == active) return
        doodlePhaseActive = active
        if (active) {
            doodleBuilt = false
            slamManager.overlayMarkCenterLocal = null
        // 0.9 — same lifetime: without it PoseFusion cannot compose a correction at all.
        slamManager.captureAnchorCam = null
            doodleCaptureJob = viewModelScope.launch {
                // Two budgets, because they bound different failures:
                //
                //  - phaseTicks caps the WHOLE phase from the moment it starts. Without it, a room where
                //    ARCore never finds a surface never arms the detect budget below, so onboarding runs
                //    forever: the tutorial is never marked complete and doodleLockActive stays latched.
                //    The drawn marks are a throwaway teaching aid — they must never be able to hold the
                //    rest of the app hostage.
                //  - detectTicks caps detection once there is actually something to build against, so a
                //    slow-to-track room doesn't eat the detection budget.
                //
                // Counted in loop ticks rather than System.currentTimeMillis(): delay() is the only thing
                // in this loop that consumes time (the native build runs off-loop on IO), so ticks are an
                // honest elapsed-time proxy that can't be skewed by a device clock adjustment mid-flow.
                val maxPhaseTicks = (DOODLE_PHASE_TIMEOUT_MS / DOODLE_CAPTURE_INTERVAL_MS).toInt()
                val maxDetectTicks = (DOODLE_LOCK_TIMEOUT_MS / DOODLE_CAPTURE_INTERVAL_MS).toInt()
                var phaseTicks = 0
                var detectTicks = 0
                while (isActive && doodlePhaseActive && !_uiState.value.doodleLocked) {
                    delay(DOODLE_CAPTURE_INTERVAL_MS)
                    if (++phaseTicks >= maxPhaseTicks) {
                        onDoodleLocked()
                        break
                    }
                    if (!_uiState.value.isAnchorEstablished || renderer?.doodleWallPlane == null) continue
                    if (doodleBuilt) {
                        // Detected: the drawing was recognised. Done — don't make the user hold the
                        // phone still to satisfy a separate stability gate.
                        onDoodleLocked()
                        break
                    }
                    requestCapture() // no onScreenTap → no tap; onDoodleCaptured builds headlessly
                    // Graceful fallback: on a featureless wall relocalization may never converge. Rather
                    // than hang forever, after the budget is spent place the artwork on the plain anchor
                    // so the user still ends up with art stuck to the wall.
                    if (++detectTicks >= maxDetectTicks) {
                        onDoodleLocked()
                        break
                    }
                }
            }
        } else {
            doodleCaptureJob?.cancel()
            doodleCaptureJob = null
        }
    }

    /** Build a doodle from a headless doodle capture; sets doodleBuilt on success. */
    private fun buildDoodle(bitmap: Bitmap, intrinsics: FloatArray?, viewMatrix: FloatArray, displayRotation: Int) {
        // Only one build in flight — captures can arrive faster than a native build completes.
        if (doodleBuildInFlight) return
        val plane = renderer?.doodleWallPlane
        val intr = intrinsics
        // The preconditions bail, which used to return in silence — BEFORE `buildSingle` and so
        // before any of its refusal reporting. A run could fail here on every capture tick and the
        // report would still say "no refusal recorded".
        //
        // The normal check is the one that matters. A zero-length normal passes every structural
        // test — non-null, six floats — and then makes `MetricMarks.backProjectToPlane` (SphereSLAM) reject every single
        // ray, because `n·d` is exactly 0 for every pixel. Guarded at the publisher too
        // (`ArRenderer.doodleWallPlane`); repeated here because this is the last place it can be
        // refused with a message attached, and a plane arriving degenerate from anywhere must not
        // reach the builder.
        val planeNormalOk = plane != null && plane.size >= 6 &&
            (plane[3] * plane[3] + plane[4] * plane[4] + plane[5] * plane[5]) > 1e-8f
        // Native hands back null only under allocation pressure severe enough that it couldn't
        // allocate a 16-float array (GraffitiJNI.cpp's nativeGetAnchorTransform) — vanishingly
        // rare, but a real possibility the Kotlin signature used to hide behind a non-null type.
        val anchor = slamManager.getAnchorTransform()
        if (!planeNormalOk || intr == null || anchor == null) {
            val reason = when {
                plane == null -> "no wall plane yet — ARCore has not produced a usable surface to project onto"
                plane.size < 6 -> "wall plane malformed (${plane.size} floats, need 6)"
                !planeNormalOk -> "wall plane has a degenerate normal — nothing can back-project onto it"
                intr == null -> "no camera intrinsics for this frame"
                else -> "native engine could not produce an anchor transform (allocation failure)"
            }
            com.hereliesaz.graffitixr.feature.ar.anchor.MetricDoodleBuilder
                .recordPreconditionFailure(reason)
            Timber.w("ARDIAG doodle capture skipped: $reason")
            slamManager.setMappingPaused(false)
            return
        }
        doodleBuildInFlight = true
        val rotated = if (displayRotation != 0) {
            val m = android.graphics.Matrix().apply { postRotate(displayRotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        } else bitmap
        val planePoint = floatArrayOf(plane[0], plane[1], plane[2])
        val planeNormal = floatArrayOf(plane[3], plane[4], plane[5])
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Capture the wall's colour/luminance so the artwork can be auto-tuned to it on the
                // swap. Computed every attempt so it's fresh even on the timeout (no-doodle) path.
                val wallStats = rotated.imageStats()
                _uiState.update { it.copy(doodleWallStats = wallStats) }

                val fp = MetricDoodleBuilder.buildSingle(
                    slamManager, rotated, viewMatrix, intr, planePoint, planeNormal, anchor,
                    // The same angle `rotated` and `intr` were built with, a dozen lines above.
                    rotationDeg = displayRotation,
                    features = _uiState.value.doodleFeatures,
                )
                if (fp != null) {
                    doodleBuilt = true
                    // No need to clear anything here: `buildSingle` already recorded its own success
                    // (null) into `MetricDoodleBuilder.lastRefusalReason` internally.
                } else {
                    // `buildSingle` already recorded WHY, from the same counts this used to
                    // re-derive by hand — see `MetricDoodleBuilder.lastRefusalReason`. Read it
                    // back only to log it; the report reads the shared field directly.
                    Timber.w(
                        "ARDIAG target capture refused: " +
                            "${MetricDoodleBuilder.lastRefusalReason}",
                    )
                }
            } catch (t: Throwable) {
                android.util.Log.w("ArViewModel", "Doodle build failed", t)
                // A throw is a refusal too, and without this the shared field would otherwise keep
                // whatever it held before this attempt — success OR failure — and let the report
                // describe a DIFFERENT capture than the one that just blew up. `buildSingle` has at
                // least one reachable throw of its own — `glViewToCvDisplay` requires a multiple of
                // 90 — and reporting a crash as an unrelated result is the failure this whole effort
                // exists to stop.
                com.hereliesaz.graffitixr.feature.ar.anchor.MetricDoodleBuilder
                    .recordPreconditionFailure("threw ${t.javaClass.simpleName}: ${t.message ?: "no message"}")
            } finally {
                // Always resume the mapping that requestCapture paused — otherwise a throw would
                // wedge SLAM with mapping paused for the rest of the session.
                slamManager.setMappingPaused(false)
                doodleBuildInFlight = false
            }
        }
    }

    fun appendDiag(text: String) {
        _uiState.update { state ->
            val existing = state.diagLog?.lines() ?: emptyList()
            // Keep enough history that the startup sequence (probe -> camera handoff -> AR session ->
            // first frames) survives long enough to be copied, even past the on-screen render heartbeat.
            val updated = (existing + text).takeLast(40).joinToString("\n")
            state.copy(diagLog = updated)
        }
    }

    // setArtworkDoodleFromComposite removed: nothing called it, and it bailed on its first line
    // (`doodleDepthBuffer ?: return`) because the ARCore depth API is disabled, so it could not have
    // registered an artwork base even if something had. [updatePaintingGuide] is the live path — it
    // re-registers the design composite whenever the design changes, descriptors-only, which is all
    // the teleological corroboration test needs.

    fun computeScanHint(isTracking: Boolean, splatCount: Int, lightLevel: Float, scanPhase: ScanPhase, sectorsCovered: Int): String? {
        if (!isTracking) return appContext.getString(DesignR.string.scan_hint_recover)
        
        return when (scanPhase) {
            ScanPhase.AMBIENT -> appContext.getString(DesignR.string.scan_hint_ambient, sectorsCovered * 30)
            ScanPhase.WALL -> {
                if (lightLevel < 0.3f) appContext.getString(DesignR.string.scan_hint_too_dark)
                else if (splatCount < 5000) appContext.getString(DesignR.string.scan_hint_build_map)
                else if (splatCount < 20000) appContext.getString(DesignR.string.scan_hint_closer)
                else if (splatCount < 40000) appContext.getString(DesignR.string.scan_hint_higher_lower)
                else null
            }
            ScanPhase.COMPLETE -> null
        }
    }

    fun setVisualizationHidden(hidden: Boolean) {
        renderer?.hideVisualization = hidden
    }

    // --- Adaptive perception throttle: device-stress signals -----------------------------------
    // Thermal pressure, power-save mode, and low battery each float perceptionSystemThrottle, which
    // MainScreen pushes to the renderer to floor perception redraws at 30 fps. The renderer also
    // self-throttles on measured frame lag; this covers the system-level signals it can't see.
    // Computed getters, NOT `by lazy`: the throttle-settings collectors launched in init call
    // recomputeSystemThrottle() (which reads powerManager) on their first emission, and DataStore can
    // replay a cached value synchronously *during construction* — before a `by lazy` delegate field
    // declared this far down the class is initialized, which NPE'd in the synthetic getter. A getter
    // has no backing field and reads appContext (a constructor param, always set), so it is safe to
    // touch at any point in construction. getSystemService returns the same cached service singleton,
    // so listener add/remove identity is preserved and the per-call cost is negligible.
    private val powerManager: android.os.PowerManager?
        get() = appContext.getSystemService(android.os.PowerManager::class.java)
    private val batteryManager: android.os.BatteryManager?
        get() = appContext.getSystemService(android.os.BatteryManager::class.java)
    // The throttle-monitoring state (thermalListener … BATTERY_TIER_LOW) is declared above the init
    // block that registers it; see the note there.

    /**
     * Folds device-stress signals into the AR rate policy. The existing perception floor (30fps via
     * perceptionSystemThrottle) is the "super battery saver" and is left exactly as-is — thermal,
     * OS power-save mode, and the coarse low-battery warning. The battery *level* tiers added here do
     * NOT touch that floor: a merely low level keeps perception at the full 60fps and only tightens
     * the adaptive idle/active rate ceilings (and caps the camera to 30fps on the next AR entry).
     */
    private fun recomputeSystemThrottle() {
        // Read + check + write atomically inside update {} — this is called from broadcast/thermal
        // listener threads, so the lambda re-runs on CAS contention with a fresh snapshot.
        _uiState.update { s ->
            val saver = powerManager?.isPowerSaveMode == true
            val tier = when {
                !s.throttleOnLowBattery || batteryPct !in 0..100 -> 0
                batteryPct <= BATTERY_TIER_LOW -> 2
                batteryPct <= BATTERY_TIER_MED -> 1
                else -> 0
            }
            // Super-saver perception floor (30fps). The battery-level tier is intentionally NOT a
            // trigger here — it only drives the rate ceilings below.
            val throttle = (s.throttleOnThermal && thermalHot) ||
                (s.throttleOnPowerSave && saver) ||
                (s.throttleOnLowBattery && batteryLow)
            // Heavy-work cadence ceilings. Raised now that the app is trimmed down: normal use (tier 0)
            // runs the idle gate at 30fps and active uncapped; the battery tiers still throttle, just
            // less aggressively than before (idle was 12/10/8, active 0/30/24).
            val idleCeiling = when (tier) { 2 -> 15; 1 -> 20; else -> 30 }
            val activeCeiling = when (tier) { 2 -> 30; 1 -> 45; else -> 0 }
            s.copy(
                perceptionSystemThrottle = throttle,
                batteryTier = tier,
                idleRateCeilingFps = idleCeiling,
                activeRateCeilingFps = activeCeiling,
            )
        }
        // The native relocalizer runs on every backend; nothing else lowers its pass rate.
        val s = _uiState.value
        val (lockedMs, huntMs) = relocBackoffMs(deviceStress(s.perceptionSystemThrottle, s.batteryTier))
        slamManager.setRelocBackoffMs(lockedMs, huntMs)
    }

    private fun startSystemThrottleMonitoring() {
      try {
        val pm = powerManager
        if (pm != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val listener = android.os.PowerManager.OnThermalStatusChangedListener { status ->
                thermalHot = status >= android.os.PowerManager.THERMAL_STATUS_MODERATE
                recomputeSystemThrottle()
            }
            try {
                pm.addThermalStatusListener(listener)
                thermalListener = listener
            } catch (_: Throwable) { /* OEM may not support it; power-save + battery still apply. */ }
        }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    android.content.Intent.ACTION_BATTERY_LOW -> batteryLow = true
                    android.content.Intent.ACTION_BATTERY_OKAY -> batteryLow = false
                    android.content.Intent.ACTION_BATTERY_CHANGED ->
                        batteryPct = batteryManager
                            ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                            ?.takeIf { it in 0..100 } ?: batteryPct
                }
                recomputeSystemThrottle()
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_BATTERY_LOW)
            addAction(android.content.Intent.ACTION_BATTERY_OKAY)
            addAction(android.content.Intent.ACTION_BATTERY_CHANGED)
            addAction(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
        powerReceiver = receiver
        recomputeSystemThrottle()
      } catch (_: Throwable) {
        // Best-effort: device-stress monitoring must never break AR construction. Without it, the
        // renderer still self-throttles on measured frame lag; only the system-stress floor is lost.
      }
    }

    private fun stopSystemThrottleMonitoring() {
        thermalListener?.let { l ->
            try { powerManager?.removeThermalStatusListener(l) } catch (_: Throwable) {}
        }
        thermalListener = null
        powerReceiver?.let { r ->
            try { appContext.unregisterReceiver(r) } catch (_: Throwable) {}
        }
        powerReceiver = null
    }

    override fun onCleared() {
        isCleared = true
        super.onCleared()
        stopSystemThrottleMonitoring()
        cameraAttitudeProvider.stop()
        if (depthEstimatorLazy.isInitialized()) depthEstimatorLazy.value.close()
        // viewModelScope is already cancelled by the time onCleared runs, so leaveSession()'s
        // viewModelScope.launch would never execute and the collaboration session would leak.
        // Cancel the local collector synchronously and tear the session down on the manager's
        // own (surviving) scope instead.
        coopStateJob?.cancel()
        coopStateJob = null
        guestEditDropJob?.cancel()
        guestEditDropJob = null
        collaborationManager.leaveSessionAsync()
        // setArMode(false) is not guaranteed to run before the ViewModel dies (process death,
        // task removal), and a registered SensorEventListener outlives it.
        stopEnvironmentSampling()
        destroyArSession()
    }
}

/**
 * Autosave decision. Each persisted quantity is compared with its own value at the last save — never
 * against a different counter (the ARCore cloud vs the wall feature map once made this true every
 * tick, rewriting the project every 30 s).
 */
internal fun shouldAutosaveWallMap(
    mapPoints: Int,
    savedMapPoints: Int,
    paintMarks: Int,
    savedPaintMarks: Int,
    progress: Float,
    savedProgress: Float,
    wallRows: Int = 0,
    savedWallRows: Int = 0,
): Boolean =
    (mapPoints > 0 && mapPoints - savedMapPoints >= ArViewModel.AUTOSAVE_POINT_DELTA) ||
        paintMarks - savedPaintMarks >= ArViewModel.AUTOSAVE_PAINT_DELTA ||
        // Self-grown Doodle rows: as valuable as paint marks, on the same delta.
        wallRows - savedWallRows >= ArViewModel.AUTOSAVE_PAINT_DELTA ||
        progress - savedProgress >= ArViewModel.AUTOSAVE_PROGRESS_DELTA
