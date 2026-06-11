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
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.hereliesaz.graffitixr.common.model.ArScanMode
import com.hereliesaz.graffitixr.common.model.MuralMethod
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.ScanPhase
import com.hereliesaz.graffitixr.common.sensor.Vec3
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import com.hereliesaz.graffitixr.common.util.isolateMarkings
import com.hereliesaz.graffitixr.common.util.eraseColorBlob
import com.hereliesaz.graffitixr.common.wearable.ConnectionState
import com.hereliesaz.graffitixr.common.wearable.WearableManager
import com.hereliesaz.graffitixr.feature.ar.coop.calibration.Mat4
import com.hereliesaz.graffitixr.feature.ar.coop.calibration.Procrustes
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.nativebridge.depth.StereoDepthProvider
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import com.hereliesaz.graffitixr.data.ProjectManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

@HiltViewModel
class ArViewModel @Inject constructor(
    private val slamManager: SlamManager,
    private val stereoProvider: StereoDepthProvider,
    private val projectRepository: ProjectRepository,
    private val settingsRepository: SettingsRepository,
    private val projectManager: com.hereliesaz.graffitixr.data.ProjectManager,
    private val collaborationManager: com.hereliesaz.graffitixr.core.collaboration.CollaborationManager,
    private val wearableManager: WearableManager,
    @param:ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArUiState())
    val uiState: StateFlow<ArUiState> = _uiState.asStateFlow()

    private val _unfreezeRequested = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val unfreezeRequested: SharedFlow<Unit> = _unfreezeRequested.asSharedFlow()

    // One-off, user-facing feedback (e.g. "camera unavailable") so AR failures surface
    // instead of leaving a silent black screen. Collected by the screen and shown as a toast.
    private val _feedback = MutableSharedFlow<com.hereliesaz.graffitixr.common.model.FeedbackEvent>(extraBufferCapacity = 4)
    val feedback: SharedFlow<com.hereliesaz.graffitixr.common.model.FeedbackEvent> = _feedback.asSharedFlow()

    private val _hostQrPayload = MutableStateFlow<String?>(null)
    val hostQrPayload: StateFlow<String?> = _hostQrPayload

    /**
     * Set by MainActivity to route incoming spectator Ops to EditorViewModel
     * without ArViewModel knowing about the editor module. Avoids the
     * ViewModel-of-ViewModel anti-pattern.
     */
    @Volatile var spectatorOpHandler: ((com.hereliesaz.graffitixr.common.model.Op) -> Unit)? = null

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

    private fun observeCoopState() {
        coopStateJob?.cancel()
        coopStateJob = viewModelScope.launch {
            collaborationManager.state.collect { newState ->
                _uiState.update { it.copy(coopSessionState = newState) }
            }
        }
    }

    fun startHosting() {
        viewModelScope.launch {
            try {
                val fingerprint = slamManager.exportFingerprint() ?: ByteArray(0)
                val projectBytes = projectManager.serializeCurrentProject()
                val qrString = collaborationManager.startHosting(
                    projectId = projectManager.currentProjectId(),
                    layerCount = projectRepository.currentProject.value?.layers?.size ?: 0,
                    fingerprintBytes = fingerprint,
                    projectBytes = projectBytes,
                    localDeviceName = android.os.Build.MODEL,
                )
                _uiState.update {
                    it.copy(
                        coopRole = com.hereliesaz.graffitixr.common.model.CoopRole.HOST,
                        coopSessionState = com.hereliesaz.graffitixr.common.model.CoopSessionState.WaitingForGuest,
                    )
                }
                _hostQrPayload.value = qrString
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(coopSessionState = com.hereliesaz.graffitixr.common.model.CoopSessionState.Ended(com.hereliesaz.graffitixr.common.model.CoopSessionState.EndReason.NetworkLost))
                }
                return@launch
            }
            // Observe collaborationManager.state and propagate (single tracked collector).
            observeCoopState()
        }
    }

    fun joinFromQr(qr: String) {
        viewModelScope.launch {
            try {
                collaborationManager.joinFromQr(
                    qr = qr,
                    localDeviceName = android.os.Build.MODEL,
                    onBulkReceived = { fingerprint, project ->
                        slamManager.alignToPeer(fingerprint)
                        projectManager.loadAsSpectator(project)
                    },
                    onOp = { op -> spectatorOpHandler?.invoke(op) },
                )
                _uiState.update { it.copy(coopRole = com.hereliesaz.graffitixr.common.model.CoopRole.GUEST) }
                observeCoopState()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(coopSessionState = com.hereliesaz.graffitixr.common.model.CoopSessionState.Ended(com.hereliesaz.graffitixr.common.model.CoopSessionState.EndReason.NetworkLost))
                }
            }
        }
    }

    fun leaveSession() {
        viewModelScope.launch {
            coopStateJob?.cancel()
            coopStateJob = null
            collaborationManager.leaveSession()
            _uiState.update { it.copy(coopRole = com.hereliesaz.graffitixr.common.model.CoopRole.NONE, coopSessionState = com.hereliesaz.graffitixr.common.model.CoopSessionState.Idle) }
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

    fun evalStartLog() {
        evalProbe.start()
        renderer?.driftCostProbe = evalProbe
        evalLogging = true
    }

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

    fun evalStartRecording() {
        session?.let { evalRecorder.startRecording(it, "eval_${android.os.SystemClock.elapsedRealtime()}") }
    }

    fun evalStopRecording() {
        session?.let { evalRecorder.stopRecording(it) }
    }

    /** A/B switch for the pose fusion (Sub-project B): on = corrected snap-back fusion, off = old toggle. */
    fun evalSetFusionEnabled(on: Boolean) { renderer?.fusionEnabled = on }

    /** A/B switch for teleological self-grow (default OFF): promote validated new marks into the fingerprint. */
    fun evalSetSelfGrowEnabled(on: Boolean) { slamManager.setSelfGrowEnabled(on) }

    // ── Glasses session lifecycle ─────────────────────────────────────────────

    fun startGlassesSession() {
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
     * ARCore-based hit test in screen coordinates → world point. Uses the
     * renderer's latest [com.google.ar.core.Frame] snapshot. Returns null if
     * no renderer is attached, no frame has been published yet, or no plane/
     * point is hit at the given screen position.
     */
    private fun arCoreHitTestToWorld(screenPoint: PointF): Vec3? {
        val frame = renderer?.latestFrame?.get() ?: return null
        return try {
            val hits = frame.hitTest(screenPoint.x, screenPoint.y)
            val pose = hits.firstOrNull()?.hitPose ?: return null
            val t = pose.translation
            Vec3(t[0], t[1], t[2])
        } catch (e: Exception) {
            Timber.w(e, "arCoreHitTestToWorld: hitTest failed")
            null
        }
    }

    /**
     * Glasses-frame point for the moment of [timestampNs]. Without a real
     * glasses-side feature-extraction pipeline (camera→world projection in
     * the glasses' own SLAM frame), this hit-tests the SAME [screenPoint] as the
     * phone tap so the src/dst point pairs actually correspond. When glasses-side
     * world lookup is wired, replace this with a real lookup keyed on [timestampNs].
     */
    private fun glassesWorldHitForTimestamp(timestampNs: Long, screenPoint: PointF): Vec3? {
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

    // Timestamps used to flip `trackingFailed` when ARCore never reaches a
    // TRACKING state within the grace window after entering AR mode. Reset on
    // every AR-mode entry; updated each time setTrackingState reports tracking.
    @Volatile private var arEntryTimestampMs: Long = 0L
    @Volatile private var lastTrackingTimestampMs: Long = 0L
    private val trackingFailureGraceMs: Long = 10_000L
    // Forced-stereo "stuck" detection must beat the 5s input-timeout ANR, so it's much shorter
    // than the general tracking-failure grace.
    private val STEREO_STUCK_GRACE_MS: Long = 3_000L

    private val isSaving = AtomicBoolean(false)
    private val lastSavedSplatCount = AtomicInteger(0)
    private var autoSaveJob: kotlinx.coroutines.Job? = null
    private var loadedProjectId: String? = null

    @Volatile
    private var pendingTapPosition: Pair<Float, Float>? = null

    private val visitedSectors = BooleanArray(36) // 10 degree sectors for higher resolution feedback

    private val eraseUndoStack = ArrayDeque<Bitmap>()
    private val eraseRedoStack = ArrayDeque<Bitmap>()
    private val eraseOpMutex = Mutex()
    private val MAX_ERASE_UNDO = 10

    init {
        NativeLibLoader.loadAll()
        viewModelScope.launch(Dispatchers.IO) {
            slamManager.loadSuperPoint(appContext.assets)
            slamManager.loadDistortionHead(appContext.assets) // optional; inert if asset absent
            slamManager.loadLowLightEnhancer(appContext.assets)
        }
        viewModelScope.launch {
            // Resolve ARCore availability up front. With com.google.ar.core
            // marked optional in the manifest, Play does not filter for ARCore
            // devices, so we may be running on hardware that ARCore does not
            // support. The result drives mode-chooser visibility and the
            // first-launch "AR unavailable" onboarding step.
            val result = ArAvailabilityChecker.check(appContext)
            val supported = result == ArAvailabilityChecker.Result.Supported ||
                result == ArAvailabilityChecker.Result.NeedsInstallOrUpdate
            _uiState.update {
                it.copy(
                    isArCoreAvailable = supported,
                    isArCoreAvailabilityResolved = true,
                )
            }
            Timber.i("ArCore availability resolved: $result (supported=$supported)")
        }
        viewModelScope.launch {
            projectRepository.currentProject.collect { project ->
                if (project != null) {
                    loadedProjectId = project.id
                    loadMapIfExists()
                    loadCloudPointsIfExists()
                    loadFingerprintIfExists()
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.arScanMode.collect { mode ->
                _uiState.update { it.copy(arScanMode = mode) }
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
        viewModelScope.launch {
            settingsRepository.showAnchorBoundary.collect { show ->
                _uiState.update { it.copy(showAnchorBoundary = show) }
            }
        }
        viewModelScope.launch {
            settingsRepository.isImperialUnits.collect { imperial ->
                _uiState.update { it.copy(isImperialUnits = imperial) }
            }
        }
    }

    // Sticky: a device proved its forced hardware-stereo path is broken (ARCore motion-stereo
    // disparity fails / VIO never tracks). Persisted; once set, sessions skip the stereo config.
    @Volatile private var forcedStereoUnstable: Boolean = false

    // One-time dual-lens depth-triangulation probe result: null = not yet probed, true = the lenses
    // produce a real depth map (adopt the stereo config), false = no usable depth / thrashes / never
    // tracks (stay mono). Persisted via SettingsRepository.stereoCapability so it runs once per install.
    @Volatile private var stereoCapable: Boolean? = null
    private val stereoProbeInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    // The in-flight session-state update (probe + init/resume/pause). Cancelled on the next update so
    // a running probe stops and releases the camera immediately when the activity pauses or AR exits.
    private var sessionUpdateJob: kotlinx.coroutines.Job? = null

    // MURAL (gaussian-splat SLAM) and the other mural methods run on a mono camera using VIO /
    // motion depth — they do NOT require hardware stereo. The mono-vs-stereo *camera config* is
    // chosen separately (probe + initArSessionLocked); it must not disable the MURAL *scan mode*.
    // So the scan mode is never auto-downgraded to Canvas; the user picks Canvas explicitly if wanted.

    fun setArScanMode(mode: ArScanMode) {
        // A user explicitly choosing Mural is a retry: clear the sticky stereo-unstable flag and let
        // the next session re-evaluate hardware stereo (recovers from any false positive).
        if (mode == ArScanMode.MURAL && forcedStereoUnstable) {
            forcedStereoUnstable = false
            viewModelScope.launch { settingsRepository.setForcedStereoUnstable(false) }
        }
        // Persist the choice so it survives app restarts (the arScanMode flow re-emits it; the direct
        // update below just makes the UI reflect it immediately).
        viewModelScope.launch { settingsRepository.setArScanMode(mode) }
        _uiState.update { it.copy(arScanMode = mode) }
    }

    fun setMuralMethod(method: MuralMethod) {
        _uiState.update { it.copy(muralMethod = method) }
        slamManager.setMuralMethod(method.ordinal)
    }

    fun setShowAnchorBoundary(show: Boolean) {
        _uiState.update { it.copy(showAnchorBoundary = show) }
        renderer?.showAnchorBoundary = show
    }

    fun setImperialUnits(imperial: Boolean) {
        _uiState.update { it.copy(isImperialUnits = imperial) }
    }

    fun onActivityResumed() {
        isActivityResumed = true
        updateSessionStateLocked()
    }

    fun onActivityPaused() {
        isActivityResumed = false
        updateSessionStateLocked()
    }

    fun setArMode(enabled: Boolean, context: Context) {
        if (enabled && !_uiState.value.isArCoreAvailable) {
            // ARCore is not supported on this device. Refuse to enter AR mode
            // rather than crashing inside Session(context). The mode chooser
            // already hides AR for unsupported devices; this is defense in
            // depth in case it's reached via deep link, restored state, etc.
            Timber.w("setArMode(true) ignored: ARCore unavailable on this device")
            return
        }
        isInArMode = enabled
        Timber.i("ARDIAG setArMode(enabled=$enabled) layers=${projectRepository.currentProject.value?.layers?.size ?: 0} scanMode=${_uiState.value.arScanMode} sessionExists=${session != null}")
        if (enabled) {
            val now = System.currentTimeMillis()
            arEntryTimestampMs = now
            lastTrackingTimestampMs = now
            if (_uiState.value.trackingFailed) {
                _uiState.update { it.copy(trackingFailed = false) }
            }
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
    fun exitArMode() {
        isInArMode = false
        isDestroying = true
        // Cancel any in-flight session update (including a running stereo probe) so it stops pumping
        // the camera and releases the session mutex before cleanup tries to acquire it.
        sessionUpdateJob?.cancel()
        if (_uiState.value.trackingFailed) {
            _uiState.update { it.copy(trackingFailed = false) }
        }
        // Dispatchers.Default, NOT viewModelScope's Main: performFullCleanupLocked calls
        // saveMapBlocking (native SLAM save), session.pause(), and session.close() — and
        // close() BLOCKS until any in-flight session.update() returns. When the GL thread
        // is wedged inside update() (camera never fed ARCore), running this on Main froze
        // the entire app on AR exit — exactly when the diag tells the user to exit.
        viewModelScope.launch(Dispatchers.Default) {
            performFullCleanupLocked()
        }
    }

    private fun updateSessionStateLocked(context: Context? = null) {
        sessionUpdateJob?.cancel()
        // Dispatchers.Default, NOT the main thread: initArSessionLocked creates/configures the ARCore
        // Session and resumeArSessionInternal calls session.resume() — which OPENS THE CAMERA and can
        // block for hundreds of ms (or hang on a wedged camera). Running that on the main thread froze
        // the whole UI (ANR). Off-main, a slow/blocked camera open leaves the UI responsive so the user
        // can back out. All session ops stay serialized by sessionMutex; _uiState and renderer access
        // are thread-safe.
        sessionUpdateJob = viewModelScope.launch(Dispatchers.Default) {
            sessionMutex.withLock {
                // First-ever AR entry on an unprobed device: while the camera is still free (no live
                // session yet), run a short throwaway stereo session in an isolated process to see
                // whether the dual lenses actually triangulate depth. Done inside the session mutex so no concurrent state
                // update can open the live session while the probe still holds the camera. Capped at a
                // few seconds and off the main thread, so a device whose motion-stereo thrashes can't
                // ANR — and we only adopt stereo if it works. Cancelling this job (AR exit / pause)
                // cancels the probe too.
                if (context != null && isInArMode && session == null && !isDestroying && stereoCapable == null) {
                    probeStereoCapability(context)
                }
                if (isInArMode && session == null && context != null && !isDestroying) {
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
                    if (awaitCameraAvailable(context, 3000L)) {
                        initArSessionLocked(context)
                    } else {
                        appendDiag("camera gate FAILED — refusing to open AR session on an unconfirmed camera")
                        _feedback.tryEmit(
                            com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(
                                "Camera is busy or unavailable — close other camera apps, then re-enter AR"
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
                appendDiag("camera handoff: ${if (freed) "available" else "TIMEOUT after ${timeoutMs}ms"}")
                freed
            } finally {
                cm.unregisterAvailabilityCallback(cb)
            }
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
            config.focusMode = Config.FocusMode.AUTO
            // LATEST_CAMERA_IMAGE so session.update() never blocks the GL render thread. On this
            // device the BLOCKING mode left the renderer stuck "Waiting for first frame" (the render
            // heartbeat never fired) — update() hung waiting for a frame that never arrived, so the
            // camera passthrough never drew. LATEST returns the most recent frame immediately, letting
            // onDrawFrame tick and draw the camera even when ARCore's perception pipeline is degraded.
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            // Detect walls so the overlay can anchor to the real surface at its true distance. Without
            // this, hit-tests only return sparse feature points and the overlay lands short of the wall.
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            
            // ARCore's ML Depth API (DepthMode.AUTOMATIC) ran a perception graph that errored
            // continuously and starved VIO into permanent kNotTracking on this hardware (confirmed via
            // logcat: feature_track_ml_depth_provider + mediapipe normal_detector RET_CHECK). It stays
            // OFF. Metric depth comes from VIO-baseline triangulation (and hardware stereo where the
            // device offers it); the wall anchor uses plane detection. Re-enabling requires first
            // solving the VIO starvation.
            val useArCoreDepthApi = false
            if (useArCoreDepthApi && s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                config.depthMode = Config.DepthMode.AUTOMATIC
                _uiState.update { it.copy(isDepthApiSupported = true) }
            } else {
                config.depthMode = Config.DepthMode.DISABLED
                _uiState.update { it.copy(isDepthApiSupported = false) }
            }
            renderer?.depthApiEnabled = useArCoreDepthApi

            s.configure(config)

            // Camera config selection. Forcing a hardware-stereo config makes ARCore run motion-stereo
            // (spherical_rectifier); on devices that list a "stereo" config but can't actually compute
            // disparity that pegs the CPU, starves VIO into kNotTracking, and ANRs the main thread with
            // a black camera. So we only select stereo when the one-time worker-thread probe has proven
            // this device's dual lenses actually triangulate depth (stereoCapable == true) and it hasn't
            // since been marked unstable. Everything else stays on the safe mono 30 FPS back config.
            val useStereo = stereoCapable == true && !forcedStereoUnstable
            var stereoActive = false
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
                // Do NOT force a camera config on the mono path. Forcing getSupportedCameraConfigs(BACK,
                // 30fps)[0] selected a config this device could open but never stream from (resume()
                // succeeds, but ARCore receives no camera image — update() blocks forever on the first
                // frame and the app ANRs). Other ARCore apps work here because they use ARCore's DEFAULT
                // camera config, which is the most broadly compatible. Leave it unset so ARCore chooses.
                Timber.i("ARDIAG dual-lens: using ARCore DEFAULT camera config cam=${s.cameraConfig.cameraId} (stereoCapable=$stereoCapable)")
            }

            // isDualLensActive / isHardwareStereoActive just reflect the camera config for the diag
            // panel; the MURAL scan mode stays available regardless (it works on mono).
            _uiState.update {
                it.copy(
                    isDualLensActive = stereoActive,
                    isHardwareStereoActive = stereoActive
                )
            }

            session = s
            _isCameraInUseByAr.value = true
            // Surface the camera-config decision ON SCREEN (not just logcat) so a black-camera report
            // tells us whether the live session is on stereo or mono — the difference between a
            // forced-stereo fault and a deeper camera-feeding fault.
            appendDiag("AR session: ${if (stereoActive) "STEREO" else "mono"} cam=${s.cameraConfig.cameraId} cap=$stereoCapable unstable=$forcedStereoUnstable")
            Timber.i("ARDIAG initArSessionLocked: session created OK (stereoActive=$stereoActive rendererAttached=${renderer != null})")

            // Critical: if the renderer is already attached, update it with the new session
            renderer?.attachSession(s)
        } catch (e: Exception) {
            Timber.e(e, "ARDIAG Failed to create ARCore session -> camera black")
            // Surface the failure instead of leaving the user on a frozen black AR screen.
            _isCameraInUseByAr.value = false
            _feedback.tryEmit(
                com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error("Couldn't start AR — your camera may be in use by another app", e)
            )
        }
    }

    /**
     * One-time dual-lens depth-triangulation probe. Runs a short throwaway ARCore session configured
     * for forced hardware stereo with the Depth API enabled, in an isolated ":probe" process, and
     * adopts dual-lens only if it both reaches TRACKING and hands back a populated depth image — proof
     * the two lenses actually triangulate depth. A device that merely lists a stereo config but can't
     * compute disparity returns an empty depth map (or thrashes and never tracks), so we cap the probe
     * and treat any failure as "mono". The result is cached in settings so this only runs once per
     * install. Must be called while no live session holds the camera.
     */
    private suspend fun probeStereoCapability(context: Context) {
        if (stereoCapable != null) return
        if (!stereoProbeInFlight.compareAndSet(false, true)) return
        try {
            // On-screen so the black "session null" startup window is explained: the probe holds the
            // camera exclusively while it runs, delaying the live session by up to its timeout.
            appendDiag("probe: start (camera held ~${StereoProbeService.PROBE_TIMEOUT_MS / 1000}s max)")
            val capable = runProbeViaService(context)
            stereoCapable = capable
            settingsRepository.setStereoCapability(if (capable) 1 else 0)
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
    }

    /**
     * Drives [StereoProbeService] in the isolated ":probe" process and returns its verdict. The broken
     * forced-stereo thrash (if any) happens entirely in that throwaway background process, so it can
     * never starve this UI process into an ANR. If the probe process dies, the binding can't be made,
     * or no reply arrives within the timeout, we conservatively return false (stay on mono).
     */
    private suspend fun runProbeViaService(context: Context): Boolean {
        val appCtx = context.applicationContext
        val result = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val replyMessenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
            if (msg.what == StereoProbeService.MSG_RESULT) result.complete(msg.arg1 == 1)
            true
        })
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    val m = Message.obtain(null, StereoProbeService.MSG_RUN_PROBE).apply {
                        replyTo = replyMessenger
                    }
                    Messenger(binder).send(m)
                } catch (e: Exception) {
                    result.complete(false)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // The :probe process died (e.g. killed after a hang) -> treat the device as mono.
                result.complete(false)
            }
        }
        val bound = try {
            appCtx.bindService(
                Intent(appCtx, StereoProbeService::class.java),
                conn,
                Context.BIND_AUTO_CREATE
            )
        } catch (e: Exception) {
            false
        }
        if (!bound) {
            try { appCtx.unbindService(conn) } catch (_: Exception) {}
            return false
        }
        return try {
            // Bound process startup + the probe's own timeout, with headroom; never block forever.
            kotlinx.coroutines.withTimeoutOrNull(StereoProbeService.PROBE_TIMEOUT_MS + 4_000L) {
                result.await()
            } ?: false
        } finally {
            try { appCtx.unbindService(conn) } catch (_: Exception) {}
        }
    }

    fun setCameraPermission(granted: Boolean) {
        _uiState.update { it.copy(hasCameraPermission = granted) }
    }

    private fun resumeArSessionInternal() {
        val s = session ?: return
        try {
            s.resume()
            isSessionResumed = true
            startAutoSave()
        } catch (e: CameraNotAvailableException) {
            Timber.e(e, "Camera not available for ARCore")
            // Permission revoked at runtime, or the camera is held elsewhere. Tell the user
            // rather than leaving a frozen preview.
            _feedback.tryEmit(
                com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error("Camera unavailable — check the camera permission, then re-enter AR", e)
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to resume ARCore session")
        }
    }

    private fun pauseArSessionInternal() {
        val s = session ?: return
        try {
            stopAutoSave()
            s.pause()
            isSessionResumed = false
        } catch (e: Exception) {
            Timber.e(e, "Failed to pause ARCore session")
        }
    }

    private suspend fun performFullCleanupLocked() {
        isDestroying = true
        sessionMutex.withLock {
            stopAutoSave()
            // Stop the renderer touching ARCore BEFORE pause()/close(). The renderer drives the
            // session under its OWN ReentrantLock, so closing under sessionMutex alone raced the
            // GL frame body (hitTest/acquireCameraImage/setCameraTextureName on a session being
            // closed) — Session is not thread-safe, and that race segfaulted ARCore internally on
            // its MTC_vio thread. detachSessionBounded confirms the GL thread is out of the frame
            // (or times out if it's wedged inside update(), in which case close() absorbs the
            // in-flight call). Saves still work: they read the renderer's sub-renderers, not the
            // ARCore session.
            renderer?.isDestroying = true
            val glThreadOut = renderer?.detachSessionBounded(1500L) ?: true
            if (!glThreadOut) {
                appendDiag("cleanup: GL thread wedged in-frame after 1500ms — closing session anyway")
            }
            saveMapBlocking()
            saveCloudPointsBlocking()
            session?.let {
                if (isSessionResumed) it.pause()
                it.close()
            }
            session = null
            renderer = null
            isSessionResumed = false
            _isCameraInUseByAr.value = false
            isDestroying = false
            clearCaptureBitmaps()
        }
    }

    /**
     * Drops the large capture bitmaps and the native depth buffer held in [ArUiState], letting
     * GC reclaim them. We deliberately do NOT call recycle(): on AR-mode teardown a background
     * fingerprint/save coroutine (onConfirmTargetCreation, setArtworkFingerprintFromComposite)
     * may still hold these same bitmap instances, and recycling them out from under it would
     * cause a "trying to use a recycled bitmap" crash. Nulling the references is deterministic
     * and safe; the platform reclaims the memory once no coroutine references them.
     */
    private fun clearCaptureBitmaps() {
        _uiState.update {
            it.copy(
                tempCaptureBitmap = null,
                annotatedCaptureBitmap = null,
                targetRawBitmap = null,
                targetDepthBuffer = null
            )
        }
    }

    fun saveMapBlocking() {
        val projectId = loadedProjectId ?: return
        val mapPath = projectManager.getMapPath(appContext, projectId)
        val cloudPath = projectManager.getCloudPointsPath(appContext, projectId)
        
        // Save both native engine state (Voxel + Mesh) and ARCore Point Cloud
        slamManager.saveModel(mapPath)
        renderer?.saveCloudPoints(cloudPath)
        
        lastSavedSplatCount.set(slamManager.getSplatCount())
        Timber.d("Atomic persistence complete: Saved all mapping components for $projectId")
    }

    fun saveCloudPointsBlocking() {
        val projectId = loadedProjectId ?: return
        val path = projectManager.getCloudPointsPath(appContext, projectId)
        renderer?.saveCloudPoints(path)
    }

    fun saveMapNow() {
        val projectId = loadedProjectId ?: return
        if (slamManager.getSplatCount() <= 0) return
        if (isSaving.get()) return

        viewModelScope.launch(Dispatchers.IO) {
            isSaving.set(true)
            try {
                saveMapBlocking()
                saveCloudPointsBlocking()
            } catch (e: Exception) {
                Timber.e(e, "Background map save failed")
            } finally {
                isSaving.set(false)
            }
        }
    }

    private fun loadFingerprintIfExists() {
        viewModelScope.launch(Dispatchers.IO) {
            val project = projectRepository.currentProject.value ?: return@launch
            val fp = project.fingerprint
            if (fp != null) {
                slamManager.restoreWallFingerprint(
                    fp.descriptorsData,
                    fp.descriptorsRows,
                    fp.descriptorsCols,
                    fp.descriptorsType,
                    fp.points3d.toFloatArray()
                )
                // Restore the distortion-head canonical patch so the head works after reload, not only
                // in the capture session. 256x256 raw gray (= sqrt(len)); inert if absent/head unloaded.
                if (fp.patchData.isNotEmpty()) {
                    val s = kotlin.math.sqrt(fp.patchData.size.toDouble()).toInt()
                    if (s * s == fp.patchData.size) slamManager.setWallPatchBytes(fp.patchData, s)
                }
            }
        }
    }

    fun saveCloudPointsNow() {
        val projectId = loadedProjectId ?: return
        val path = projectManager.getCloudPointsPath(appContext, projectId)
        renderer?.saveCloudPoints(path)
    }

    private fun loadCloudPointsIfExists() {
        val projectId = loadedProjectId ?: return
        val path = projectManager.getCloudPointsPath(appContext, projectId)
        if (File(path).exists()) {
            renderer?.scheduleCloudPointsLoad(path)
        }
    }

    private fun loadMapIfExists() {
        val projectId = loadedProjectId ?: return
        val mapPath = projectManager.getMapPath(appContext, projectId)
        val cloudPath = projectManager.getCloudPointsPath(appContext, projectId)
        
        if (File(mapPath).exists() || File(cloudPath).exists()) {
            if (projectRepository.currentProject.value?.id == loadedProjectId && slamManager.getSplatCount() > 0) return
            
            viewModelScope.launch(Dispatchers.IO) {
                if (File(mapPath).exists()) {
                    slamManager.loadModel(mapPath)
                    lastSavedSplatCount.set(slamManager.getSplatCount())
                }
                if (File(cloudPath).exists()) {
                    renderer?.scheduleCloudPointsLoad(cloudPath)
                }
                Timber.d("Atomic persistence complete: Loaded available mapping components for $projectId")
            }
        } else {
            // No saved map for this project — clear any lingering SLAM map left over in native
            // state from a previous session, so a new AR session doesn't get stuck trying to
            // relocalize a stale map (kMapTracking with 0 structure matches -> black camera).
            viewModelScope.launch(Dispatchers.IO) {
                slamManager.clearMap()
                lastSavedSplatCount.set(0)
            }
        }
    }

    private fun startAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(30000)
                val current = slamManager.getSplatCount()
                if (current > 0 && Math.abs(current - lastSavedSplatCount.get()) > 500) {
                    saveMapNow()
                }
            }
        }
    }

    private fun stopAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = null
    }

    fun destroyArSession() {
        // Off-main for the same reason as exitArMode: session.close() blocks until any
        // in-flight session.update() returns, which never happens when the GL thread is
        // wedged on a camera that isn't feeding ARCore.
        viewModelScope.launch(Dispatchers.Default) {
            performFullCleanupLocked()
        }
    }

    fun attachSessionToRenderer(r: ArRenderer?) {
        // Synchronous on purpose. ArRenderer.attachSession() already guards the session read with
        // its own ReentrantLock and null-checks, and onDrawFrame swallows a torn-down session, so
        // the read-then-attach is safe. Dispatching this onto a coroutine (to "fix" a benign
        // TOCTOU) instead created a worse race: it could run AFTER performFullCleanupLocked nulled
        // `renderer`, resurrecting a reference to an already-destroyed renderer.
        renderer = r
        renderer?.stereoProvider = stereoProvider
        renderer?.onCameraNotFeeding = { onCameraNotFeeding() }
        renderer?.attachSession(session)
        loadCloudPointsIfExists()
    }

    fun setTrackingState(
        isTracking: Boolean,
        splatCount: Int,
        immutableSplatCount: Int,
        isDepthApiSupported: Boolean,
        cameraYaw: Float = 0f,
        distanceToAnchorMeters: Float = -1f,
        anchorRelativeDirection: Triple<Float, Float, Float>? = null,
        isDualLens: Boolean = false,
        isHardwareStereo: Boolean = false,
        centerDepth: Float = -1f,
        visConf: Float = 0f,
        globConf: Float = 0f
    ) {
        val progress = if (isTracking) slamManager.getPaintingProgress() else _uiState.value.paintingProgress

        val nowMs = System.currentTimeMillis()
        if (isTracking) lastTrackingTimestampMs = nowMs
        val trackingFailed = isInArMode &&
                !isTracking &&
                arEntryTimestampMs > 0L &&
                (nowMs - lastTrackingTimestampMs) > trackingFailureGraceMs

        val sector = (((cameraYaw % 360f) + 360f) % 360f / 10f).toInt().coerceIn(0, 35)
        visitedSectors[sector] = true
        val sectorsCovered = visitedSectors.count { it }
        val mappingProgress = sectorsCovered / 36f
        val sectorMask = (0..35).fold(0L) { acc, i -> if (visitedSectors[i]) acc or (1L shl i) else acc }

        _uiState.update { state ->
            val newPhase = when (state.scanPhase) {
                ScanPhase.AMBIENT -> {
                    // Task: Canvas mode (CLOUD_POINTS) skips the ambient 360-scan.
                    // All MURAL modes MUST complete the 360-scan for spatial memory.
                    if (state.arScanMode == ArScanMode.CLOUD_POINTS || sectorsCovered >= 36) {
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
                splatCount = splatCount,
                immutableSplatCount = immutableSplatCount,
                isDepthApiSupported = isDepthApiSupported,
                paintingProgress = progress,
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
                isDualLensActive = isDualLens,
                isHardwareStereoActive = isHardwareStereo,
                currentCenterDepth = centerDepth,
                visibleSplatConfidenceAvg = visConf,
                globalSplatConfidenceAvg = globConf,
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
        if (!forcedStereoUnstable &&
            isInArMode &&
            isHardwareStereo &&
            !isTracking &&
            splatCount == 0 &&
            _uiState.value.arScanMode == ArScanMode.MURAL &&
            arEntryTimestampMs > 0L &&
            (nowMs - lastTrackingTimestampMs) > STEREO_STUCK_GRACE_MS
        ) {
            recoverFromBrokenStereo()
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
    private fun onCameraNotFeeding() {
        if (forcedStereoUnstable) {
            appendDiag("camera not feeding (mono/unstable) — exit and re-enter AR to retry")
            return
        }
        if (!_uiState.value.isHardwareStereoActive) {
            // Mono config and STILL no frames: the camera itself isn't feeding ARCore (confirmed not a
            // stereo fault). Auto-recreating the session here is NOT safe — recreating ARCore on a wedged
            // camera crashed natively — so surface it and let the user re-enter AR. Root cause is under
            // investigation (the 'camera handoff' / 'AR session' diag lines localize it).
            appendDiag("camera not feeding on mono — exit and re-enter AR to retry")
            _feedback.tryEmit(
                com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(
                    "Camera isn't delivering frames — exit AR and try again"
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
     * skip stereo entirely. Recoverable: the user re-selecting Mural clears the flag (see
     * setArScanMode). Runs on Dispatchers.Default — pause()/resume() block on the camera — and
     * detaches the renderer first because reconfiguring a Session the GL thread is concurrently
     * driving under a different lock is the not-thread-safe race that crashes ARCore natively.
     */
    private fun recoverFromBrokenStereo() {
        if (!stereoRecoveryInFlight.compareAndSet(false, true)) return
        forcedStereoUnstable = true
        _uiState.update { it.copy(trackingFailed = false) }
        viewModelScope.launch { settingsRepository.setForcedStereoUnstable(true) }
        viewModelScope.launch(Dispatchers.Default) {
            sessionMutex.withLock {
                val s = session
                if (s == null || isDestroying) return@withLock
                val wasResumed = isSessionResumed
                // Get the GL thread out of the frame body before touching the session config.
                val glThreadOut = renderer?.detachSessionBounded(1500L) ?: true
                if (!glThreadOut) {
                    appendDiag("stereo recovery: GL thread wedged in-frame after 1500ms — reconfiguring anyway")
                }
                if (wasResumed) pauseArSessionInternal()
                try {
                    val monoConfigs = s.getSupportedCameraConfigs(CameraConfigFilter(s).apply {
                        facingDirection = CameraConfig.FacingDirection.BACK
                        targetFps = EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30)
                    })
                    if (monoConfigs.isNotEmpty()) s.cameraConfig = monoConfigs[0]
                    _uiState.update { it.copy(isDualLensActive = false, isHardwareStereoActive = false) }
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

    fun updatePaintingGuide(bitmap: Bitmap) {
        // The design composite (design layers only — NO wall texture) is the teleological "base
        // understanding": the registered overlay the clean wall frame is validated against. Re-register
        // it as the artwork base whenever the design changes so painting-progress (and the staged
        // self-grow) track the current design. Descriptors-only here (no capture depth); the
        // 3D-dependent promotion step is staged. compareAndSet drops overlapping registrations — the
        // next stable design state registers once the in-flight one finishes.
        if (!artworkRegInFlight.compareAndSet(false, true)) return
        val intr = _uiState.value.targetIntrinsics ?: FloatArray(4)
        val view = _uiState.value.targetCaptureViewMatrix ?: FloatArray(16)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                slamManager.setArtworkFingerprint(bitmap, null, 0, 0, 0, intr, view)
            } finally {
                artworkRegInFlight.set(false)
            }
        }
    }

    fun onFreezeRequested(bitmap: Bitmap) {
        _uiState.update { it.copy(freezePreviewBitmap = bitmap) }
        renderer?.hideVisualization = true
    }

    fun onFreezeDismissed() {
        _uiState.update { it.copy(freezePreviewBitmap = null) }
        renderer?.hideVisualization = false
    }

    fun onUnfreezeRequested() {
        viewModelScope.launch {
            _unfreezeRequested.emit(Unit)
            onFreezeDismissed()
        }
    }

    fun onTargetCaptured(
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
        tapDistanceMeters: Float
    ) {
        val tapPos = pendingTapPosition
        val extent = computePhysicalExtent(depthBuffer, depthBufW, depthBufH, colorW, colorH, intrinsics, depthBufStride)

        val rotatedBmp = if (displayRotation != 0) {
            val matrix = android.graphics.Matrix().apply { postRotate(displayRotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else bitmap

        if (tapPos != null) {
            pendingTapPosition = null
            
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
                    targetRawBitmap = bitmap,
                    targetDisplayRotation = displayRotation,
                    targetDepthBuffer = depthBuffer,
                    targetDepthWidth = colorW,
                    targetDepthHeight = colorH,
                    targetDepthBufferWidth = depthBufW,
                    targetDepthBufferHeight = depthBufH,
                    targetDepthStride = depthBufStride,
                    targetIntrinsics = intrinsics,
                    targetCaptureViewMatrix = viewMatrix,
                    targetPhysicalExtent = extent,
                    isCaptureRequested = false,
                    tempCaptureBitmap = rotatedBmp,
                    annotatedCaptureBitmap = rotatedBmp.isolateMarkings(),
                    unwarpPoints = initialPoints
                )
            }
            return
        }

        extent?.let { (halfW, halfH) ->
            renderer?.updateOverlayExtent(halfW, halfH)
        }

        _uiState.update {
            it.copy(
                tempCaptureBitmap = rotatedBmp,
                annotatedCaptureBitmap = rotatedBmp.isolateMarkings(),
                targetRawBitmap = bitmap,
                targetDisplayRotation = displayRotation,
                targetDepthBuffer = depthBuffer,
                targetDepthWidth = colorW,
                targetDepthHeight = colorH,
                targetDepthBufferWidth = depthBufW,
                targetDepthBufferHeight = depthBufH,
                targetDepthStride = depthBufStride,
                targetIntrinsics = intrinsics,
                targetCaptureViewMatrix = viewMatrix,
                targetPhysicalExtent = extent,
                isCaptureRequested = false
            )
        }
    }

    fun applyEraseToMask(nx: Float, ny: Float, radius: Float) {
        val currentMask = _uiState.value.annotatedCaptureBitmap ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val copy = currentMask.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = android.graphics.Canvas(copy)
            val paint = android.graphics.Paint().apply {
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
                isAntiAlias = true
            }
            canvas.drawCircle(nx * copy.width, ny * copy.height, radius * copy.width, paint)
            _uiState.update { it.copy(annotatedCaptureBitmap = copy) }
            // Reflect the erase in the keypoint overlay: re-detect within the updated mask so dots in
            // erased regions disappear. Debounced so a drag (many erase calls) recomputes once it pauses.
            scheduleKeypointRecompute()
        }
    }

    private var keypointRecomputeJob: kotlinx.coroutines.Job? = null
    private fun scheduleKeypointRecompute() {
        keypointRecomputeJob?.cancel()
        keypointRecomputeJob = viewModelScope.launch {
            kotlinx.coroutines.delay(250)
            computeTargetKeypoints()
        }
    }


    fun onScreenTap(nx: Float, ny: Float) {
        pendingTapPosition = nx to ny
        // Hand the tap to the renderer so it can measure the camera→point distance at that pixel
        // (and add a fusion support anchor) on the GL thread during capture.
        renderer?.pendingCaptureTap = floatArrayOf(nx, ny)
        requestCapture()
    }

    fun clearTapHighlights() {
        _uiState.update { it.copy(tapMarks = emptyList()) }
        pendingTapPosition = null
    }

    fun computePhysicalExtent(
        depthBuffer: ByteBuffer?,
        depthW: Int, depthH: Int,
        colorW: Int, colorH: Int,
        intrinsics: FloatArray?,
        stride: Int
    ): Pair<Float, Float>? {
        if (depthBuffer == null || intrinsics == null) return null
        
        val cx = depthW / 2
        val cy = depthH / 2
        val byteOffset = cy * stride + cx * 2
        
        if (byteOffset + 2 > depthBuffer.limit()) return null
        
        val raw = depthBuffer.getShort(byteOffset).toInt() and 0xFFFF
        val depthMm = raw and 0x1FFF
        if (depthMm <= 0) return null
        
        val depthM = depthMm / 1000f
        val fx = intrinsics[0]
        val fy = intrinsics[1]

        val halfW = (depthM * (colorW / 2f) / fx) * 0.18f
        val halfH = (depthM * (colorH / 2f) / fy) * 0.18f
        
        return halfW to halfH
    }

    fun setTempCapture(bitmap: Bitmap?) {
        _uiState.update { it.copy(tempCaptureBitmap = bitmap) }
    }

    fun setAnnotatedCapture(bitmap: Bitmap?) {
        _uiState.update { it.copy(annotatedCaptureBitmap = bitmap) }
        computeTargetKeypoints()
    }

    /**
     * Detect the REAL fingerprint features on the captured target (same detector as
     * generateFingerprint, restricted to the current mask) and publish them normalized for the
     * refinement overlay, so the user sees exactly what will anchor and can erase the spurious ones.
     */
    private fun computeTargetKeypoints() {
        val raw = _uiState.value.tempCaptureBitmap
        if (raw == null) {
            _uiState.update { it.copy(targetKeypoints = emptyList()) }
            return
        }
        val mask = _uiState.value.annotatedCaptureBitmap
        val w = raw.width.toFloat().coerceAtLeast(1f)
        val h = raw.height.toFloat().coerceAtLeast(1f)
        viewModelScope.launch(Dispatchers.IO) {
            val pts = slamManager.getFingerprintKeypoints(raw, mask)
                .map { androidx.compose.ui.geometry.Offset(it.first / w, it.second / h) }
            _uiState.update { it.copy(targetKeypoints = pts) }
        }
    }




    fun onCaptureConsumed() {
        clearCaptureBitmaps()
        slamManager.setMappingPaused(false)
    }

    fun setInitialAnchorFromCapture() {
        renderer?.pendingAnchorEstablishment = true
    }

    fun requestCapture() {
        slamManager.setSplatsVisible(false)
        slamManager.setMappingPaused(true)
        _uiState.update { it.copy(isCaptureRequested = true) }
    }

    fun requestExport(callback: (Bitmap) -> Unit) {
        renderer?.onExportCaptured = { bmp ->
            viewModelScope.launch(Dispatchers.Main) {
                callback(bmp)
            }
        }
        renderer?.exportRequested = true
    }

    fun onCaptureRequestHandled() {
        _uiState.update { it.copy(isCaptureRequested = false) }
        slamManager.setSplatsVisible(true)
    }

    fun setUnwarpPoints(points: List<Offset>) {
        _uiState.update { it.copy(unwarpPoints = points) }
    }

    fun setActiveUnwarpPoint(index: Int) {
        _uiState.update { it.copy(activeUnwarpPointIndex = index) }
    }

    fun setMagnifierPosition(offset: Offset) {
        _uiState.update { it.copy(magnifierPosition = offset) }
    }

    fun updateMaskPath(path: Path) {
        _uiState.update { it.copy(maskPath = path) }
    }

    fun toggleFlashlight() {
        _uiState.update { it.copy(isFlashlightOn = !it.isFlashlightOn) }
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

    fun setArtworkFingerprintFromComposite(bitmap: Bitmap) {
        val ui = _uiState.value
        val depth = ui.targetDepthBuffer ?: return
        val intr = ui.targetIntrinsics ?: return
        val view = ui.targetCaptureViewMatrix ?: return
        
        viewModelScope.launch(Dispatchers.IO) {
            slamManager.setArtworkFingerprint(
                bitmap,
                depth,
                ui.targetDepthBufferWidth,
                ui.targetDepthBufferHeight,
                ui.targetDepthStride,
                intr,
                view
            )
            
            // Progress is reset when new features are added
            _uiState.update { it.copy(paintingProgress = 0f) }
        }
    }

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

    fun retriggerPlaneDetection() {
        renderer?.isInPlaneRealignment = true
    }

    fun setPlaneConfirmationBorder(show: Boolean) {
        renderer?.showBorderForConfirmation = show
    }

    fun setVisualizationHidden(hidden: Boolean) {
        renderer?.hideVisualization = hidden
    }

    override fun onCleared() {
        super.onCleared()
        // viewModelScope is already cancelled by the time onCleared runs, so leaveSession()'s
        // viewModelScope.launch would never execute and the collaboration session would leak.
        // Cancel the local collector synchronously and tear the session down on the manager's
        // own (surviving) scope instead.
        coopStateJob?.cancel()
        coopStateJob = null
        collaborationManager.leaveSessionAsync()
        destroyArSession()
    }
}
