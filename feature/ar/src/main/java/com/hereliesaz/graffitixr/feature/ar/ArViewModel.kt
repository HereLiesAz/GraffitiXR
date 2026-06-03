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

    fun setArScanMode(mode: ArScanMode) {
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
        if (_uiState.value.trackingFailed) {
            _uiState.update { it.copy(trackingFailed = false) }
        }
        viewModelScope.launch {
            performFullCleanupLocked()
        }
    }

    private fun updateSessionStateLocked(context: Context? = null) {
        viewModelScope.launch {
            sessionMutex.withLock {
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

            // Dual-lens mandate: use a hardware-stereo camera config whenever ARCore offers one on
            // this device; fall back to the mono 30 FPS back config otherwise. We only select a
            // stereo config that ARCore explicitly lists as supported, so devices that don't expose
            // one (e.g. Pixel 5) stay exactly on the old mono path — no change, no risk. Where stereo
            // IS selected the device gets true two-lens depth; watch for the historical caveat that
            // some phones' VIO destabilized under forced stereo (grep logcat "dual-lens").
            val stereoConfigs = try {
                s.getSupportedCameraConfigs(CameraConfigFilter(s).apply {
                    facingDirection = CameraConfig.FacingDirection.BACK
                    stereoCameraUsage = java.util.EnumSet.of(CameraConfig.StereoCameraUsage.REQUIRE_AND_USE)
                })
            } catch (e: Exception) {
                Timber.w(e, "dual-lens: stereo config query failed")
                emptyList()
            }

            val stereoActive: Boolean
            if (stereoConfigs.isNotEmpty()) {
                s.cameraConfig = stereoConfigs[0]
                stereoActive = true
                Timber.i("dual-lens: HARDWARE STEREO selected — cameraId=${stereoConfigs[0].cameraId} imageSize=${stereoConfigs[0].imageSize} (${stereoConfigs.size} stereo config(s))")
            } else {
                val monoConfigs = s.getSupportedCameraConfigs(CameraConfigFilter(s).apply {
                    facingDirection = CameraConfig.FacingDirection.BACK
                    targetFps = EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30)
                })
                if (monoConfigs.isNotEmpty()) s.cameraConfig = monoConfigs[0]
                stereoActive = false
                Timber.i("dual-lens: no hardware-stereo config on this device — using mono")
            }
            _uiState.update { it.copy(isDualLensActive = stereoActive, isHardwareStereoActive = stereoActive) }

            session = s
            _isCameraInUseByAr.value = true
            
            // Critical: if the renderer is already attached, update it with the new session
            renderer?.attachSession(s)
        } catch (e: Exception) {
            Timber.e(e, "Failed to create ARCore session")
            // Surface the failure instead of leaving the user on a frozen black AR screen.
            _isCameraInUseByAr.value = false
            _feedback.tryEmit(
                com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error("Couldn't start AR — your camera may be in use by another app", e)
            )
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
                evalLiveMetrics = if (evalLogging) evalProbe.lastMetrics else state.evalLiveMetrics
            )
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
