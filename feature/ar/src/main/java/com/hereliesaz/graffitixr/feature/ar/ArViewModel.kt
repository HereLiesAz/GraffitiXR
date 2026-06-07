// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt
package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    // How long the one-time worker-thread stereo probe waits for VIO to reach TRACKING before
    // concluding the device can't run dual-lens. Off the main thread, so it can't ANR.
    private val STEREO_PROBE_TIMEOUT_MS: Long = 3_000L

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
                _uiState.update { it.copy(arScanMode = mode.coerceToCapability()) }
            }
        }
        viewModelScope.launch {
            settingsRepository.forcedStereoUnstable.collect { unstable ->
                forcedStereoUnstable = unstable
                if (unstable) {
                    deviceCanDoMural = false
                    _uiState.update { it.copy(arScanMode = it.arScanMode.coerceToCapability()) }
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.stereoCapability.collect { cap ->
                stereoCapable = when (cap) {
                    1 -> true
                    0 -> false
                    else -> null
                }
                // A device whose stereo can't track can't do MURAL — fall its scan mode back to Canvas.
                if (stereoCapable == false) {
                    deviceCanDoMural = false
                    _uiState.update { it.copy(arScanMode = it.arScanMode.coerceToCapability()) }
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

    // Whether this device can run MURAL scanning. MURAL needs dual-lens (hardware-stereo) depth
    // triangulation; set from the session's hardware-stereo support. Defaults true until known.
    @Volatile private var deviceCanDoMural: Boolean = true

    // Sticky: a device proved its forced hardware-stereo path is broken (ARCore motion-stereo
    // disparity fails / VIO never tracks). Persisted; once set, sessions skip the stereo config.
    @Volatile private var forcedStereoUnstable: Boolean = false

    // One-time hardware-stereo probe result: null = not yet probed, true = stereo tracks (adopt
    // dual-lens), false = stereo thrashes / never tracks (stay mono). Persisted via
    // SettingsRepository.stereoCapability so the probe only runs once per install.
    @Volatile private var stereoCapable: Boolean? = null
    private val stereoProbeInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    // The in-flight session-state update (probe + init/resume/pause). Cancelled on the next update so
    // a running probe stops and releases the camera immediately when the activity pauses or AR exits.
    private var sessionUpdateJob: kotlinx.coroutines.Job? = null

    // MURAL requires depth; on devices that can't do it, fall back to Canvas (CLOUD_POINTS).
    private fun ArScanMode.coerceToCapability(): ArScanMode =
        if (!deviceCanDoMural && this == ArScanMode.MURAL) ArScanMode.CLOUD_POINTS else this

    fun setArScanMode(mode: ArScanMode) {
        // A user explicitly choosing Mural is a retry: clear the sticky stereo-unstable flag and let
        // the next session re-evaluate hardware stereo (recovers from any false positive).
        if (mode == ArScanMode.MURAL && forcedStereoUnstable) {
            forcedStereoUnstable = false
            deviceCanDoMural = true
            viewModelScope.launch { settingsRepository.setForcedStereoUnstable(false) }
        }
        _uiState.update { it.copy(arScanMode = mode.coerceToCapability()) }
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
        viewModelScope.launch {
            performFullCleanupLocked()
        }
    }

    private fun updateSessionStateLocked(context: Context? = null) {
        sessionUpdateJob?.cancel()
        sessionUpdateJob = viewModelScope.launch {
            sessionMutex.withLock {
                // First-ever AR entry on an unprobed device: while the camera is still free (no live
                // session yet), run a short throwaway stereo session on a worker thread to see whether
                // dual-lens VIO actually tracks. Done inside the session mutex so no concurrent state
                // update can open the live session while the probe still holds the camera. Capped at a
                // few seconds and off the main thread, so a device whose motion-stereo thrashes can't
                // ANR — and we only adopt stereo if it works. Cancelling this job (AR exit / pause)
                // cancels the probe too.
                if (context != null && isInArMode && session == null && !isDestroying && stereoCapable == null) {
                    probeStereoCapability(context)
                }
                if (isInArMode && session == null && context != null && !isDestroying) {
                    initArSessionLocked(context)
                }

                if (isActivityResumed && isInArMode && !isSessionResumed && !isDestroying && session != null) {
                    resumeArSessionInternal()
                } else if ((!isActivityResumed || !isInArMode) && isSessionResumed) {
                    pauseArSessionInternal()
                }
            }
        }
    }

    private fun initArSessionLocked(context: Context) {
        if (session != null || isDestroying) return
        Timber.i("ARDIAG initArSessionLocked: creating session")
        try {
            val s = Session(context)
            val config = Config(s)
            config.focusMode = Config.FocusMode.AUTO
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
            // this device's stereo actually tracks (stereoCapable == true) and it hasn't since been
            // marked unstable. Everything else stays on the safe mono 30 FPS back config.
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
                val monoConfigs = s.getSupportedCameraConfigs(CameraConfigFilter(s).apply {
                    facingDirection = CameraConfig.FacingDirection.BACK
                    targetFps = EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30)
                })
                if (monoConfigs.isNotEmpty()) s.cameraConfig = monoConfigs[0]
                Timber.i("ARDIAG dual-lens: using mono camera config (stereoCapable=$stereoCapable)")
            }

            // MURAL needs dual-lens depth triangulation; without hardware stereo it falls back to Canvas.
            deviceCanDoMural = stereoActive
            _uiState.update {
                it.copy(
                    isDualLensActive = stereoActive,
                    isHardwareStereoActive = stereoActive,
                    arScanMode = it.arScanMode.coerceToCapability()
                )
            }

            session = s
            _isCameraInUseByAr.value = true
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
     * One-time hardware-stereo capability probe. Runs a short throwaway ARCore session configured for
     * forced hardware stereo on a worker thread (its own offscreen EGL context), pumps [Session.update]
     * for a few seconds and adopts dual-lens only if VIO reaches TRACKING. A device whose motion-stereo
     * is broken never tracks (and thrashes), so we cap the probe and treat any failure as "mono". The
     * result is cached in settings so this only runs once per install. Must be called while no live
     * session holds the camera.
     */
    private suspend fun probeStereoCapability(context: Context) {
        if (stereoCapable != null) return
        if (!stereoProbeInFlight.compareAndSet(false, true)) return
        try {
            val capable = withContext(Dispatchers.Default) { runStereoProbe(context) { isActive } }
            stereoCapable = capable
            settingsRepository.setStereoCapability(if (capable) 1 else 0)
            Timber.i("ARDIAG stereo probe complete: capable=$capable")
        } catch (e: Exception) {
            // A cancelled probe (AR exit / pause) must NOT be cached as a permanent mono verdict —
            // let cancellation propagate so the device stays "unprobed" and re-probes next entry.
            if (e is kotlinx.coroutines.CancellationException) throw e
            stereoCapable = false
            settingsRepository.setStereoCapability(0)
            Timber.w(e, "ARDIAG stereo probe threw -> treating device as mono")
        } finally {
            stereoProbeInFlight.set(false)
        }
    }

    /** Blocking stereo probe; runs entirely on the caller's (background) thread. Returns true iff a
     *  forced-stereo session reaches TRACKING within [STEREO_PROBE_TIMEOUT_MS]. [isActive] is polled
     *  so the loop bails and releases the camera immediately when the coroutine is cancelled. */
    private fun runStereoProbe(context: Context, isActive: () -> Boolean): Boolean {
        var egl: ProbeEgl? = null
        var probe: Session? = null
        try {
            probe = Session(context)
            val cfg = Config(probe).apply {
                focusMode = Config.FocusMode.AUTO
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                depthMode = Config.DepthMode.DISABLED
                planeFindingMode = Config.PlaneFindingMode.DISABLED
            }
            probe.configure(cfg)
            val stereoConfigs = probe.getSupportedCameraConfigs(CameraConfigFilter(probe).apply {
                facingDirection = CameraConfig.FacingDirection.BACK
                stereoCameraUsage = EnumSet.of(CameraConfig.StereoCameraUsage.REQUIRE_AND_USE)
            })
            if (stereoConfigs.isEmpty()) {
                Timber.i("ARDIAG stereo probe: device exposes no hardware-stereo config")
                return false
            }
            probe.cameraConfig = stereoConfigs[0]

            egl = ProbeEgl()
            probe.setCameraTextureName(egl.cameraTextureId)
            probe.resume()

            // Monotonic clock: a wall-clock jump (NTP sync, user change) must not extend or truncate
            // the probe window.
            val deadline = android.os.SystemClock.elapsedRealtime() + STEREO_PROBE_TIMEOUT_MS
            while (android.os.SystemClock.elapsedRealtime() < deadline && isActive()) {
                val frame = try {
                    probe.update()
                } catch (e: Exception) {
                    Timber.w(e, "ARDIAG stereo probe: update() failed")
                    return false
                }
                if (frame.camera.trackingState == com.google.ar.core.TrackingState.TRACKING) {
                    return true
                }
                Thread.sleep(33)
            }
            return false
        } catch (e: Exception) {
            Timber.w(e, "ARDIAG stereo probe: setup failed")
            return false
        } finally {
            try { probe?.pause() } catch (_: Exception) {}
            try { probe?.close() } catch (_: Exception) {}
            egl?.release()
        }
    }

    /** Minimal offscreen EGL context (1x1 pbuffer) with a single GL_TEXTURE_EXTERNAL_OES texture, so
     *  the probe session has somewhere to bind camera frames while we pump [Session.update]. */
    private class ProbeEgl {
        private var display: android.opengl.EGLDisplay = android.opengl.EGL14.EGL_NO_DISPLAY
        private var ctx: android.opengl.EGLContext = android.opengl.EGL14.EGL_NO_CONTEXT
        private var surface: android.opengl.EGLSurface = android.opengl.EGL14.EGL_NO_SURFACE
        val cameraTextureId: Int

        init {
            // Validate every EGL step: a failure on an odd device/emulator must surface as an
            // exception (probe falls back to mono) rather than running GL on an invalid context.
            display = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY)
            if (display == android.opengl.EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")
            val version = IntArray(2)
            if (!android.opengl.EGL14.eglInitialize(display, version, 0, version, 1)) {
                throw RuntimeException("eglInitialize failed")
            }
            val cfgAttribs = intArrayOf(
                android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
                android.opengl.EGL14.EGL_SURFACE_TYPE, android.opengl.EGL14.EGL_PBUFFER_BIT,
                android.opengl.EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val numConfig = IntArray(1)
            if (!android.opengl.EGL14.eglChooseConfig(display, cfgAttribs, 0, configs, 0, 1, numConfig, 0) ||
                numConfig[0] <= 0 || configs[0] == null) {
                throw RuntimeException("eglChooseConfig failed")
            }
            val ctxAttribs = intArrayOf(android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, android.opengl.EGL14.EGL_NONE)
            ctx = android.opengl.EGL14.eglCreateContext(display, configs[0], android.opengl.EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (ctx == android.opengl.EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")
            val surfAttribs = intArrayOf(android.opengl.EGL14.EGL_WIDTH, 1, android.opengl.EGL14.EGL_HEIGHT, 1, android.opengl.EGL14.EGL_NONE)
            surface = android.opengl.EGL14.eglCreatePbufferSurface(display, configs[0], surfAttribs, 0)
            if (surface == android.opengl.EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreatePbufferSurface failed")
            if (!android.opengl.EGL14.eglMakeCurrent(display, surface, surface, ctx)) {
                throw RuntimeException("eglMakeCurrent failed")
            }
            val tex = IntArray(1)
            android.opengl.GLES20.glGenTextures(1, tex, 0)
            android.opengl.GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
            cameraTextureId = tex[0]
        }

        fun release() {
            if (display != android.opengl.EGL14.EGL_NO_DISPLAY) {
                android.opengl.EGL14.eglMakeCurrent(
                    display, android.opengl.EGL14.EGL_NO_SURFACE,
                    android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT
                )
                if (surface != android.opengl.EGL14.EGL_NO_SURFACE) android.opengl.EGL14.eglDestroySurface(display, surface)
                if (ctx != android.opengl.EGL14.EGL_NO_CONTEXT) android.opengl.EGL14.eglDestroyContext(display, ctx)
                android.opengl.EGL14.eglTerminate(display)
            }
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
        viewModelScope.launch {
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

    private val stereoRecoveryInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * The forced hardware-stereo path is broken on this device — switch the LIVE session to the
     * mono camera config (pause → set mono → resume) so the broken motion-stereo disparity stops
     * thrashing the tracker, and persist the flag so future sessions skip stereo entirely.
     * Recoverable: the user re-selecting Mural clears the flag (see setArScanMode).
     */
    private fun recoverFromBrokenStereo() {
        if (!stereoRecoveryInFlight.compareAndSet(false, true)) return
        forcedStereoUnstable = true
        deviceCanDoMural = false
        _uiState.update { it.copy(arScanMode = it.arScanMode.coerceToCapability(), trackingFailed = false) }
        viewModelScope.launch { settingsRepository.setForcedStereoUnstable(true) }
        viewModelScope.launch {
            sessionMutex.withLock {
                val s = session
                if (s == null || isDestroying) return@withLock
                val wasResumed = isSessionResumed
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
            val updated = (existing + text).takeLast(10).joinToString("\n")
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
