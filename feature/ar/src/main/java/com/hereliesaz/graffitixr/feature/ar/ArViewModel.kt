package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.hereliesaz.graffitixr.common.model.ArScanMode
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.nativebridge.depth.StereoDepthProvider
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@HiltViewModel
class ArViewModel @Inject constructor(
    private val slamManager: SlamManager,
    private val stereoProvider: StereoDepthProvider,
    private val projectRepository: ProjectRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArUiState())
    val uiState: StateFlow<ArUiState> = _uiState.asStateFlow()

    private var session: Session? = null
    private var renderer: ArRenderer? = null

    private val _isCameraInUseByAr = MutableStateFlow(false)
    val isCameraInUseByAr = _isCameraInUseByAr.asStateFlow()

    private var isActivityResumed = false
    private var isInArMode = false
    private var isSessionResumed = false
    private var isDestroying = false

    private val sessionMutex = Mutex()

    // Auto-save: prevent concurrent saves and track last-saved splat count
    private val isSaving = AtomicBoolean(false)
    private var lastSavedSplatCount = 0
    private var autoSaveJob: kotlinx.coroutines.Job? = null

    // Phase 4: tap position stored here; consumed when the next onTargetCaptured fires.
    @Volatile private var pendingTapPosition: Pair<Float, Float>? = null

    init {
        // isAnchorEstablished tracks whether the current project has a saved fingerprint.
        viewModelScope.launch {
            projectRepository.currentProject.collect { project ->
                _uiState.update { it.copy(isAnchorEstablished = project?.fingerprint != null) }
            }
        }
        // Keep arScanMode in sync with the persisted setting and propagate to the renderer.
        viewModelScope.launch {
            settingsRepository.arScanMode.collect { mode ->
                _uiState.update { it.copy(arScanMode = mode) }
                renderer?.scanMode = mode
            }
        }
        // Phase 5: propagate showAnchorBoundary to renderer.
        viewModelScope.launch {
            settingsRepository.showAnchorBoundary.collect { show ->
                _uiState.update { it.copy(showAnchorBoundary = show) }
                renderer?.showAnchorBoundary = show
            }
        }
    }

    fun setArScanMode(mode: ArScanMode) {
        viewModelScope.launch {
            settingsRepository.setArScanMode(mode)
        }
    }

    fun onActivityResumed() {
        viewModelScope.launch {
            sessionMutex.withLock {
                isActivityResumed = true
                updateSessionStateLocked()
            }
        }
    }

    fun onActivityPaused() {
        viewModelScope.launch {
            sessionMutex.withLock {
                isActivityResumed = false
                updateSessionStateLocked()
            }
        }
    }

    fun setArMode(enabled: Boolean, context: Context) {
        viewModelScope.launch {
            sessionMutex.withLock {
                if (isInArMode == enabled) return@withLock
                isInArMode = enabled

                if (enabled) {
                    isDestroying = false
                    initArSessionLocked(context)
                    updateSessionStateLocked()
                } else {
                    isDestroying = true
                    performFullCleanupLocked()
                    isDestroying = false
                }
            }
        }
    }

    private fun updateSessionStateLocked() {
        val shouldBeRunning = isActivityResumed && isInArMode && !isDestroying
        if (shouldBeRunning && !isSessionResumed) {
            resumeArSessionInternal()
        } else if (!shouldBeRunning && isSessionResumed) {
            pauseArSessionInternal()
        }
    }

    private fun initArSessionLocked(context: Context) {
        if (session == null) {
            try {
                _isCameraInUseByAr.value = true
                val newSession = Session(context)
                val filter = CameraConfigFilter(newSession).apply {
                    targetFps = EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30)
                }
                val supportedConfigs = newSession.getSupportedCameraConfigs(filter)
                if (supportedConfigs.isNotEmpty()) {
                    newSession.cameraConfig = supportedConfigs[0]
                }

                val config = Config(newSession).apply {
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    depthMode = Config.DepthMode.AUTOMATIC
                    focusMode = Config.FocusMode.AUTO
                }

                try {
                    val flashlightModeClass = Class.forName("com.google.ar.core.Config\$FlashlightMode")
                    val offMode = flashlightModeClass.getField("OFF").get(null)
                    val method = config.javaClass.getMethod("setFlashlightMode", flashlightModeClass)
                    method.invoke(config, offMode)
                } catch (e: Exception) {}

                newSession.configure(config)
                session = newSession
                renderer?.attachSession(newSession)

                viewModelScope.launch(Dispatchers.IO) {
                    slamManager.loadSuperPoint(context.assets)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _isCameraInUseByAr.value = false
            }
        }
    }

    private fun resumeArSessionInternal() {
        val s = session ?: return
        try {
            s.resume()
            isSessionResumed = true
            _isCameraInUseByAr.value = true
            slamManager.setRelocEnabled(true)
            renderer?.attachSession(s)
            loadMapIfExists()
            loadCloudPointsIfExists()
            loadFingerprintIfExists()
            startAutoSave()
        } catch (e: CameraNotAvailableException) {
            e.printStackTrace()
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun pauseArSessionInternal() {
        if (!isSessionResumed) return
        isSessionResumed = false
        try {
            renderer?.attachSession(null)
            session?.pause()
            slamManager.setRelocEnabled(false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        stopAutoSave()
        saveMapNow()           // persist SLAM splats on every background/screen-off
        saveCloudPointsNow()   // Phase 2: persist ARCore feature points
    }

    private suspend fun performFullCleanupLocked() {
        renderer?.attachSession(null)
        if (isSessionResumed) {
            try { session?.pause() } catch (e: Exception) {}
            isSessionResumed = false
        }
        delay(150)
        val s = session
        session = null
        s?.let {
            try { it.close() } catch (e: Exception) {}
        }
        _isCameraInUseByAr.value = false
        slamManager.setRelocEnabled(false)
    }

    // ── Map persistence ───────────────────────────────────────────────────────

    /** Save the current map to the active project's map.bin. No-op if no project open. */
    private fun saveMapNow() {
        val project = projectRepository.currentProject.value ?: return
        if (isSaving.getAndSet(true)) return  // already saving
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val mapPath = run {
                    val root = java.io.File(appContext.filesDir, "projects/${project.id}")
                    if (!root.exists()) root.mkdirs()
                    java.io.File(root, "map.bin").absolutePath
                }
                slamManager.saveModel(mapPath)
                lastSavedSplatCount = slamManager.getSplatCount()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isSaving.set(false)
            }
        }
    }

    /** Re-arm the native engine with the saved target fingerprint if the project has one. */
    private fun loadFingerprintIfExists() {
        val fp = projectRepository.currentProject.value?.fingerprint ?: return
        viewModelScope.launch(Dispatchers.IO) {
            slamManager.setTargetFingerprint(
                fp.descriptorsData,
                fp.descriptorsRows,
                fp.descriptorsCols,
                fp.descriptorsType,
                fp.points3d.toFloatArray()
            )
        }
    }

    // ── Cloud points persistence (Phase 2) ───────────────────────────────────

    private fun cloudPointsPath(projectId: String): String {
        val root = java.io.File(appContext.filesDir, "projects/$projectId")
        if (!root.exists()) root.mkdirs()
        return java.io.File(root, "cloud_points.bin").absolutePath
    }

    private fun saveCloudPointsNow() {
        val project = projectRepository.currentProject.value ?: return
        val r = renderer ?: return
        if (_uiState.value.arScanMode != com.hereliesaz.graffitixr.common.model.ArScanMode.CLOUD_POINTS) return
        viewModelScope.launch(Dispatchers.IO) {
            try { r.saveCloudPoints(cloudPointsPath(project.id)) } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun loadCloudPointsIfExists() {
        val project = projectRepository.currentProject.value ?: return
        if (_uiState.value.arScanMode != com.hereliesaz.graffitixr.common.model.ArScanMode.CLOUD_POINTS) return
        val path = cloudPointsPath(project.id)
        if (java.io.File(path).exists()) {
            renderer?.scheduleCloudPointsLoad(path)
        }
    }

    /** Load map.bin into native if the in-memory map is empty and a save exists. */
    private fun loadMapIfExists() {
        val project = projectRepository.currentProject.value ?: return
        if (slamManager.getSplatCount() > 0) return  // already have live data, don't overwrite
        viewModelScope.launch(Dispatchers.IO) {
            val mapPath = run {
                val root = java.io.File(appContext.filesDir, "projects/${project.id}")
                if (!root.exists()) root.mkdirs()
                java.io.File(root, "map.bin").absolutePath
            }
            if (File(mapPath).exists()) {
                slamManager.loadModel(mapPath)
                lastSavedSplatCount = slamManager.getSplatCount()
            }
        }
    }

    /** Start periodic auto-save every 30 seconds, and also when splat count grows by 2000. */
    private fun startAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(30_000)
                val current = slamManager.getSplatCount()
                if (current > 0 && current != lastSavedSplatCount) {
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
        viewModelScope.launch(Dispatchers.Main.immediate) {
            sessionMutex.withLock {
                if (session == null) return@withLock
                isInArMode = false
                isDestroying = true
                performFullCleanupLocked()
                isDestroying = false
            }
        }
    }

    fun attachSessionToRenderer(arRenderer: ArRenderer?) {
        this.renderer = arRenderer
        if (arRenderer != null) {
            arRenderer.scanMode = _uiState.value.arScanMode
            arRenderer.showAnchorBoundary = _uiState.value.showAnchorBoundary
            if (session != null && isSessionResumed) {
                arRenderer.attachSession(session)
            }
        }
    }

    fun setTrackingState(isTracking: Boolean, splatCount: Int, isDepthApiSupported: Boolean) {
        _uiState.update {
            it.copy(
                isScanning = isTracking,
                splatCount = splatCount,
                isDepthApiSupported = isDepthApiSupported,
                scanHint = computeScanHint(
                    isTracking = isTracking,
                    splatCount = splatCount,
                    lightLevel = it.lightLevel
                )
            )
        }
    }

    fun onTargetCaptured(
        bitmap: Bitmap,
        depthBuffer: ByteBuffer?,
        colorW: Int, colorH: Int,
        depthBufW: Int, depthBufH: Int, depthBufStride: Int,
        intrinsics: FloatArray?,
        viewMatrix: FloatArray
    ) {
        val tapPos = pendingTapPosition
        if (tapPos != null) {
            // Phase 4 — Tap capture: accumulate the tap point and run ORB detection so the user
            // sees green-highlighted features proving the app recognizes the painted mark.
            pendingTapPosition = null
            _uiState.update {
                it.copy(
                    tempCaptureBitmap = bitmap,
                    annotatedCaptureBitmap = null,
                    tapHighlightKeypoints = it.tapHighlightKeypoints + tapPos,
                    // Store depth/intrinsics for the eventual fingerprint generation.
                    targetDepthBuffer = depthBuffer,
                    targetDepthWidth = colorW,
                    targetDepthHeight = colorH,
                    targetDepthBufferWidth = depthBufW,
                    targetDepthBufferHeight = depthBufH,
                    targetDepthStride = depthBufStride,
                    targetIntrinsics = intrinsics,
                    targetCaptureViewMatrix = viewMatrix,
                    targetPhysicalExtent = computePhysicalExtent(depthBuffer, depthBufW, depthBufH, colorW, colorH, intrinsics)
                )
            }
            viewModelScope.launch(Dispatchers.IO) {
                // Annotated bitmap has ORB feature circles drawn on it; displayed green-tinted
                // in the AR viewport so the user sees that the painted marks were recognized.
                val annotated = slamManager.annotateKeypoints(bitmap)
                _uiState.update { it.copy(annotatedCaptureBitmap = annotated) }
            }
            return
        }

        // Normal (non-tap) capture flow.
        val extent = computePhysicalExtent(depthBuffer, depthBufW, depthBufH, colorW, colorH, intrinsics)
        extent?.let { (halfW, halfH) -> renderer?.updateOverlayExtent(halfW, halfH) }

        _uiState.update {
            it.copy(
                tempCaptureBitmap = bitmap,
                annotatedCaptureBitmap = null,  // cleared until annotation completes
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
        viewModelScope.launch(Dispatchers.IO) {
            val annotated = slamManager.annotateKeypoints(bitmap)
            _uiState.update { it.copy(annotatedCaptureBitmap = annotated) }
        }
    }

    /**
     * Phase 4: Called when the user taps a painted reference mark in the live camera view.
     * Triggers an immediate frame capture; when it arrives via [onTargetCaptured], the position
     * is added to [ArUiState.tapHighlightKeypoints] and ORB detection runs to show green highlights.
     */
    fun onScreenTap(nx: Float, ny: Float) {
        pendingTapPosition = Pair(nx, ny)
        renderer?.captureRequested = true
    }

    /** Phase 4: Clear all accumulated tap highlight positions (called by "Retake" / "Clear"). */
    fun clearTapHighlights() {
        pendingTapPosition = null
        _uiState.update {
            it.copy(
                tapHighlightKeypoints = emptyList(),
                annotatedCaptureBitmap = null,
                tempCaptureBitmap = null
            )
        }
    }

    /** Phase 5: Toggle the anchor boundary overlay. */
    fun setShowAnchorBoundary(show: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowAnchorBoundary(show)
        }
    }

    /**
     * Compute physical half-extents (meters) for the overlay quad from the center depth pixel.
     * Returns null if depth data is unavailable.
     */
    private fun computePhysicalExtent(
        depthBuffer: ByteBuffer?,
        depthW: Int, depthH: Int,
        colorW: Int, colorH: Int,
        intrinsics: FloatArray?
    ): Pair<Float, Float>? {
        if (depthBuffer == null || intrinsics == null || depthW == 0 || depthH == 0) return null
        val fx = intrinsics[0]; val fy = intrinsics[1]
        if (fx == 0f || fy == 0f) return null

        // Read DEPTH16 center pixel (little-endian, depth in mm in lower 13 bits)
        val centerIdx = (depthH / 2 * depthW + depthW / 2) * 2
        if (centerIdx + 1 >= depthBuffer.capacity()) return null
        val lo = depthBuffer.get(centerIdx).toInt() and 0xFF
        val hi = depthBuffer.get(centerIdx + 1).toInt() and 0xFF
        val depthMm = (lo or (hi shl 8)) and 0x1FFF
        if (depthMm == 0) return null
        val d = depthMm / 1000f

        return Pair((colorW / 2f) * d / fx, (colorH / 2f) * d / fy)
    }

    /**
     * Bake the composited layer artwork into the target fingerprint so SLAM can relocalize to
     * the finished artwork (not just bare wall texture). Call this after the user locks placement.
     */
    fun addLayerFeaturesToSLAM(composite: Bitmap) {
        val state = _uiState.value
        val depthBuffer = state.targetDepthBuffer ?: return
        val intrinsics = state.targetIntrinsics ?: return
        val viewMatrix = state.targetCaptureViewMatrix ?: return
        val depthW = state.targetDepthBufferWidth
        val depthH = state.targetDepthBufferHeight
        val depthStride = if (state.targetDepthStride > 0) state.targetDepthStride else depthW * 2
        if (depthW == 0 || depthH == 0) return

        viewModelScope.launch(Dispatchers.IO) {
            slamManager.addLayerFeatures(composite, depthBuffer, depthW, depthH, depthStride, intrinsics, viewMatrix)
        }
    }

    fun onCaptureConsumed() {
        slamManager.setSplatsVisible(true)
        _uiState.update { it.copy(tempCaptureBitmap = null) }
    }

    fun setUnwarpPoints(points: List<Offset>) {
        _uiState.update { it.copy(unwarpPoints = points) }
    }

    fun setTempCapture(bitmap: Bitmap?) {
        _uiState.update { it.copy(tempCaptureBitmap = bitmap) }
    }

    fun requestCapture() {
        slamManager.setSplatsVisible(false)
        _uiState.update { it.copy(isCaptureRequested = true, tempCaptureBitmap = null) }
    }

    fun onCaptureRequestHandled() {
        _uiState.update { it.copy(isCaptureRequested = false) }
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

    fun restoreSplats() {
        slamManager.setSplatsVisible(true)
    }

    fun captureKeyframe() {
    }

    fun toggleFlashlight() {
        val isOn = !_uiState.value.isFlashlightOn
        _uiState.update { it.copy(isFlashlightOn = isOn) }
        renderer?.updateFlashlight(isOn)
    }

    fun ensureEngineInitialized() {
        slamManager.ensureInitialized()
    }

    fun onCameraFrameForStereo(buffer: ByteBuffer, width: Int, height: Int) {
        stereoProvider.submitFrame(buffer, width, height, System.currentTimeMillis())
    }

    fun onCameraFrameForStereo(image: ImageProxy) {
        stereoProvider.submitFrame(image.planes[0].buffer, image.width, image.height, System.currentTimeMillis())
        image.close()
    }

    fun updateLightLevel(level: Float) {
        _uiState.update {
            it.copy(
                lightLevel = level,
                scanHint = computeScanHint(
                    isTracking = it.isScanning,
                    splatCount = it.splatCount,
                    lightLevel = level
                )
            )
        }
    }

    fun appendDiag(text: String) {
        _uiState.update { it.copy(diagLog = text) }
    }

    /**
     * Returns a short, specific coaching message during the scan phase, or null
     * once 50 000 splats have been collected (scan complete).
     *
     * Priority order (most blocking issue first):
     *   1. Too dark  → ARCore depth is unreliable and feature tracking fails
     *   2. Not tracking → the user needs to move to re-acquire
     *   3. Tracking but slow growth → not enough parallax / coverage
     */
    private fun computeScanHint(
        isTracking: Boolean,
        splatCount: Int,
        lightLevel: Float
    ): String? {
        if (splatCount >= 50_000) return null
        return when {
            lightLevel < 0.15f ->
                "Too dark — move to a brighter area or use the flashlight"
            lightLevel < 0.30f ->
                "Low light — more light will improve depth accuracy"
            !isTracking ->
                "Tracking lost — move slowly and point at textured surfaces"
            splatCount < 500 ->
                "Point the camera at nearby walls, floors, or objects"
            splatCount < 5_000 ->
                "Keep moving — sweep the camera across all nearby surfaces"
            else ->
                "Good — keep scanning to cover more of the environment"
        }
    }
}
