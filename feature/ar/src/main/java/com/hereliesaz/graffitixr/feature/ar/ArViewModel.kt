// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt
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
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.hereliesaz.graffitixr.common.model.ArScanMode
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.ScanPhase
import com.hereliesaz.graffitixr.common.util.isolateMarkings
import com.hereliesaz.graffitixr.common.util.eraseColorBlob
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.nativebridge.depth.StereoDepthProvider
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
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
    @param:ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArUiState())
    val uiState: StateFlow<ArUiState> = _uiState.asStateFlow()

    private val _unfreezeRequested = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val unfreezeRequested: SharedFlow<Unit> = _unfreezeRequested.asSharedFlow()

    private var session: Session? = null
    private var renderer: ArRenderer? = null

    private val _isCameraInUseByAr = MutableStateFlow(false)
    val isCameraInUseByAr = _isCameraInUseByAr.asStateFlow()

    private var isActivityResumed = false
    private var isInArMode = false
    private var isSessionResumed = false
    private var isDestroying = false
    private val sessionMutex = Mutex()

    private val isSaving = AtomicBoolean(false)
    private val lastSavedSplatCount = AtomicInteger(0)
    private var autoSaveJob: kotlinx.coroutines.Job? = null
    private var loadedProjectId: String? = null

    @Volatile
    private var pendingTapPosition: Pair<Float, Float>? = null

    private val visitedSectors = BooleanArray(12)

    private val eraseUndoStack = ArrayDeque<Bitmap>()
    private val eraseRedoStack = ArrayDeque<Bitmap>()
    private val eraseOpMutex = Mutex()
    private val MAX_ERASE_UNDO = 10

    init {
        viewModelScope.launch {
            projectRepository.currentProject.collect { project ->
                val established = project?.fingerprint != null
                _uiState.update { it.copy(isAnchorEstablished = established) }
                renderer?.anchorEstablished = established
                renderer?.hideVisualization = established
                if (project?.id != loadedProjectId) {
                    loadedProjectId = null
                }
            }
        }

        viewModelScope.launch {
            settingsRepository.arScanMode.collect { mode ->
                _uiState.update { it.copy(arScanMode = mode) }
                renderer?.scanMode = mode
                
                // Attune voxel size: Mural (Splats) = 8mm, Canvas (Points) = 2mm
                val voxelSize = if (mode == ArScanMode.GAUSSIAN_SPLATS) 0.008f else 0.002f
                slamManager.setVoxelSize(voxelSize)

                visitedSectors.fill(false)
                _uiState.update { it.copy(scanPhase = ScanPhase.AMBIENT, ambientSectorsCovered = 0) }
            }
        }

        viewModelScope.launch {
            settingsRepository.showAnchorBoundary.collect { show ->
                _uiState.update { it.copy(showAnchorBoundary = show) }
                renderer?.showAnchorBoundary = show
            }
        }

        viewModelScope.launch {
            settingsRepository.isImperialUnits.collect { imperial ->
                _uiState.update { it.copy(isImperialUnits = imperial) }
            }
        }
    }

    fun setArScanMode(mode: ArScanMode) {
        viewModelScope.launch { settingsRepository.setArScanMode(mode) }
    }

    fun setShowAnchorBoundary(show: Boolean) {
        viewModelScope.launch { settingsRepository.setShowAnchorBoundary(show) }
    }

    fun setImperialUnits(imperial: Boolean) {
        viewModelScope.launch { settingsRepository.setImperialUnits(imperial) }
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

    private suspend fun updateSessionStateLocked() {
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

                val filter = CameraConfigFilter(newSession)
                val supportedConfigs = newSession.getSupportedCameraConfigs(filter)

                // Prioritize "dual lens" (stereo camera) and hardware depth sensor usage for superior world refinement.
                val bestConfig = supportedConfigs.find {
                    it.stereoCameraUsage == CameraConfig.StereoCameraUsage.REQUIRE_AND_USE &&
                            it.depthSensorUsage == CameraConfig.DepthSensorUsage.REQUIRE_AND_USE
                } ?: supportedConfigs.find {
                    it.stereoCameraUsage == CameraConfig.StereoCameraUsage.REQUIRE_AND_USE
                } ?: supportedConfigs.find {
                    it.depthSensorUsage == CameraConfig.DepthSensorUsage.REQUIRE_AND_USE
                } ?: supportedConfigs.firstOrNull()

                if (bestConfig != null) {
                    newSession.cameraConfig = bestConfig
                }

                val config = newSession.config
                config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                config.depthMode = Config.DepthMode.AUTOMATIC
                config.focusMode = Config.FocusMode.AUTO
                
                // Attempt to enable dual-camera mode using safe reflection to avoid compilation errors
                // on some SDK environments while still requesting the feature for hardware that supports it.
                try {
                    val dualModeClass = Class.forName("com.google.ar.core.Config\$DualCameraMode")
                    val enabledEnum = dualModeClass.getField("ENABLED").get(null)
                    val isSupported = newSession.javaClass.getMethod("isDualCameraModeSupported", dualModeClass).invoke(newSession, enabledEnum) as Boolean
                    if (isSupported) {
                        config.javaClass.getMethod("setDualCameraMode", dualModeClass).invoke(config, enabledEnum)
                    }
                } catch (e: Exception) {
                    // Fallback to standard depth mode if dual-camera API is unavailable
                }

                newSession.configure(config)
                session = newSession
                renderer?.attachSession(newSession)

                viewModelScope.launch(Dispatchers.IO) {
                    slamManager.loadSuperPoint(context.assets)
                }
            } catch (e: UnavailableException) {
                Timber.e(e, "ARCore unavailable")
                _uiState.update { it.copy(isArCoreAvailable = false) }
                _isCameraInUseByAr.value = false
            } catch (e: Exception) {
                Timber.e(e, "ARCore session init failed")
                _isCameraInUseByAr.value = false
            }
        }
    }

    fun setCameraPermission(granted: Boolean) {
        _uiState.update { it.copy(hasCameraPermission = granted) }
    }

    private suspend fun resumeArSessionInternal() {
        val s = session ?: return
        try {
            s.resume()
            isSessionResumed = true
            _isCameraInUseByAr.value = true
            slamManager.setRelocEnabled(true)
            renderer?.attachSession(s)

            val projectId = projectRepository.currentProject.value?.id
            val hasExistingData = slamManager.getSplatCount() > 0
                    || File(appContext.filesDir, "projects/$projectId/map.bin").exists()
                    || File(appContext.filesDir, "projects/$projectId/cloud_points.bin").exists()
            visitedSectors.fill(false)
            _uiState.update {
                it.copy(
                    scanPhase = if (hasExistingData) ScanPhase.COMPLETE else ScanPhase.AMBIENT,
                    ambientSectorsCovered = if (hasExistingData) 12 else 0
                )
            }

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

    private suspend fun pauseArSessionInternal() {
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
        saveMapBlocking()
        saveCloudPointsBlocking()
    }

    private suspend fun performFullCleanupLocked() {
        stopAutoSave()
        renderer?.attachSession(null)
        if (isSessionResumed) {
            try { session?.pause() } catch (e: Exception) {}
            isSessionResumed = false
        }
        slamManager.setRelocEnabled(false)
        saveMapBlocking()
        saveCloudPointsBlocking()
        delay(100)
        val s = session
        session = null
        s?.let { try { it.close() } catch (e: Exception) {} }
        _isCameraInUseByAr.value = false
    }

    suspend fun saveMapBlocking() {
        val project = projectRepository.currentProject.value ?: return
        if (slamManager.getSplatCount() <= 0) return
        while (isSaving.get()) { delay(50) }
        withContext(Dispatchers.IO) {
            try {
                val root = File(appContext.filesDir, "projects/${project.id}")
                if (!root.exists()) root.mkdirs()
                // MANDATE: Keep all visible splats during save (threshold 0.1)
                slamManager.pruneByConfidence(0.1f)
                slamManager.saveModel(File(root, "map.bin").absolutePath)
                lastSavedSplatCount.set(slamManager.getSplatCount())
                loadedProjectId = project.id
            } catch (e: Exception) { Timber.e(e, "Failed to save map") }
        }
    }

    suspend fun saveCloudPointsBlocking() {
        val project = projectRepository.currentProject.value ?: return
        val currentMode = settingsRepository.arScanMode.first()
        if (currentMode != ArScanMode.CLOUD_POINTS) return
        val r = renderer ?: return
        withContext(Dispatchers.IO) {
            try { r.saveCloudPoints(cloudPointsPath(project.id)) }
            catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun saveMapNow() {
        val project = projectRepository.currentProject.value ?: return
        if (slamManager.getSplatCount() <= 0) return
        if (isSaving.getAndSet(true)) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val root = File(appContext.filesDir, "projects/${project.id}")
                if (!root.exists()) root.mkdirs()
                // MANDATE: Keep all visible splats during auto-save (threshold 0.1)
                slamManager.pruneByConfidence(0.1f)
                slamManager.saveModel(File(root, "map.bin").absolutePath)
                lastSavedSplatCount.set(slamManager.getSplatCount())
                loadedProjectId = project.id
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isSaving.set(false)
            }
        }
    }

    internal fun loadFingerprintIfExists() {
        val fp = projectRepository.currentProject.value?.fingerprint ?: return
        viewModelScope.launch(Dispatchers.IO) {
            slamManager.restoreWallFingerprint(
                fp.descriptorsData,
                fp.descriptorsRows,
                fp.descriptorsCols,
                fp.descriptorsType,
                fp.points3d.toFloatArray()
            )
        }
    }

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
            try {
                r.saveCloudPoints(cloudPointsPath(project.id))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun loadCloudPointsIfExists() {
        val project = projectRepository.currentProject.value ?: return
        val currentMode = settingsRepository.arScanMode.first()
        if (currentMode != ArScanMode.CLOUD_POINTS) return

        val path = cloudPointsPath(project.id)
        if (java.io.File(path).exists()) {
            renderer?.scheduleCloudPointsLoad(path)
        }
    }

    private fun loadMapIfExists() {
        val project = projectRepository.currentProject.value ?: return
        if (project.id == loadedProjectId && slamManager.getSplatCount() > 0) return

        viewModelScope.launch(Dispatchers.IO) {
            val root = File(appContext.filesDir, "projects/${project.id}")
            if (!root.exists()) root.mkdirs()
            val mapFile = File(root, "map.bin")
            if (mapFile.exists()) {
                slamManager.loadModel(mapFile.absolutePath)
                lastSavedSplatCount.set(slamManager.getSplatCount())
                loadedProjectId = project.id
            }
        }
    }

    private fun startAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                // Throttle auto-save to once every 60 seconds to reduce disk I/O and battery drain.
                delay(60_000)
                val current = slamManager.getSplatCount()
                // Throttled: only save if map has significantly changed (> 500 new/updated splats)
                if (current > 0 && kotlin.math.abs(current - lastSavedSplatCount.get()) > 500) {
                    saveMapNow()
                }
                if (_uiState.value.arScanMode == ArScanMode.CLOUD_POINTS) {
                    val project = projectRepository.currentProject.value ?: continue
                    val r = renderer ?: continue
                    try { r.saveCloudPoints(cloudPointsPath(project.id)) }
                    catch (e: Exception) { Timber.e(e, "Auto-save cloud points failed") }
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
            arRenderer.stereoProvider = stereoProvider
            arRenderer.scanMode = _uiState.value.arScanMode
            arRenderer.showAnchorBoundary = _uiState.value.showAnchorBoundary
            arRenderer.anchorEstablished = _uiState.value.isAnchorEstablished
            if (session != null && isSessionResumed) {
                arRenderer.attachSession(session)
            }
        }
    }

    fun setTrackingState(isTracking: Boolean, splatCount: Int, isDepthApiSupported: Boolean, cameraYaw: Float = 0f, distanceToAnchorMeters: Float = -1f, anchorRelativeDirection: Triple<Float, Float, Float>? = null) {
        val progress = if (isTracking) slamManager.getPaintingProgress() else _uiState.value.paintingProgress

        val sector = (((cameraYaw % 360f) + 360f) % 360f / 30f).toInt().coerceIn(0, 11)
        visitedSectors[sector] = true
        val sectorsCovered = visitedSectors.count { it }

        _uiState.update { state ->
            val newPhase = when (state.scanPhase) {
                ScanPhase.AMBIENT -> if (sectorsCovered >= 6) ScanPhase.WALL else ScanPhase.AMBIENT
                ScanPhase.WALL -> if (splatCount >= 30_000) ScanPhase.COMPLETE else ScanPhase.WALL
                ScanPhase.COMPLETE -> ScanPhase.COMPLETE
            }
            state.copy(
                isScanning = isTracking,
                splatCount = splatCount,
                isDepthApiSupported = isDepthApiSupported,
                paintingProgress = progress,
                scanPhase = newPhase,
                ambientSectorsCovered = sectorsCovered,
                scanHint = computeScanHint(
                    isTracking = isTracking,
                    splatCount = splatCount,
                    lightLevel = state.lightLevel,
                    scanPhase = newPhase,
                    sectorsCovered = sectorsCovered
                ),
                distanceToAnchorMeters = distanceToAnchorMeters,
                anchorRelativeDirection = anchorRelativeDirection
            )
        }
    }

    fun updatePaintingGuide(composite: android.graphics.Bitmap) {
        setArtworkFingerprintFromComposite(composite)
    }

    fun onFreezeRequested(composite: Bitmap) {
        val depthWarning = _uiState.value.targetDepthBuffer.let { it == null || it.capacity() == 0 }
        viewModelScope.launch(Dispatchers.Default) {
            val annotated = slamManager.annotateKeypoints(composite)
            _uiState.update { it.copy(freezePreviewBitmap = annotated, freezeDepthWarning = depthWarning) }
        }
    }

    fun onFreezeDismissed() {
        _uiState.update { it.copy(freezePreviewBitmap = null) }
    }

    fun onUnfreezeRequested() {
        _uiState.update { it.copy(freezePreviewBitmap = null) }
        viewModelScope.launch { _unfreezeRequested.emit(Unit) }
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
        displayRotation: Int
    ) {
        val tapPos = pendingTapPosition
        val extent = computePhysicalExtent(depthBuffer, depthBufW, depthBufH, colorW, colorH, intrinsics, depthBufStride)

        val rotatedBmp = if (displayRotation != 0) {
            val matrix = android.graphics.Matrix().apply { postRotate(displayRotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else bitmap

        if (tapPos != null) {
            pendingTapPosition = null
            
            // INITIALIZE: Default unwarp quad to the corners of the screen/view
            val w = 1080f 
            val h = 1920f
            val padding = 200f
            val initialPoints = listOf(
                Offset(padding, padding),
                Offset(w - padding, padding),
                Offset(w - padding, h - padding),
                Offset(padding, h - padding)
            )

            _uiState.update {
                it.copy(
                    tapHighlightKeypoints = it.tapHighlightKeypoints + tapPos,
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
                    annotatedCaptureBitmap = null,
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
                annotatedCaptureBitmap = null,
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
        viewModelScope.launch {
            val annotated = withContext(Dispatchers.Default) { slamManager.annotateKeypoints(rotatedBmp) }
            _uiState.update { it.copy(annotatedCaptureBitmap = annotated) }
        }
    }

    fun beginErase() {
        val current = _uiState.value.tempCaptureBitmap ?: return
        if (eraseUndoStack.size >= MAX_ERASE_UNDO) eraseUndoStack.removeFirst()
        eraseUndoStack.addLast(current)
        eraseRedoStack.clear()
        _uiState.update { it.copy(canUndoErase = true, canRedoErase = false) }
    }

    fun eraseAtPoint(nx: Float, ny: Float) {
        viewModelScope.launch(Dispatchers.Default) {
            eraseOpMutex.withLock {
                val currentBmp = _uiState.value.tempCaptureBitmap ?: return@withLock
                val updated = currentBmp.eraseColorBlob(nx, ny)
                _uiState.update { it.copy(tempCaptureBitmap = updated) }
            }
        }
    }

    fun undoErase() {
        val prev = eraseUndoStack.removeLastOrNull() ?: return
        val current = _uiState.value.tempCaptureBitmap
        if (current != null && eraseRedoStack.size < MAX_ERASE_UNDO) eraseRedoStack.addLast(current)
        _uiState.update {
            it.copy(
                tempCaptureBitmap = prev,
                canUndoErase = eraseUndoStack.isNotEmpty(),
                canRedoErase = eraseRedoStack.isNotEmpty()
            )
        }
    }

    fun redoErase() {
        val next = eraseRedoStack.removeLastOrNull() ?: return
        val current = _uiState.value.tempCaptureBitmap
        if (current != null && eraseUndoStack.size < MAX_ERASE_UNDO) eraseUndoStack.addLast(current)
        _uiState.update {
            it.copy(
                tempCaptureBitmap = next,
                canUndoErase = eraseUndoStack.isNotEmpty(),
                canRedoErase = eraseRedoStack.isNotEmpty()
            )
        }
    }

    private fun clearEraseHistory() {
        eraseUndoStack.clear()
        eraseRedoStack.clear()
        _uiState.update { it.copy(canUndoErase = false, canRedoErase = false) }
    }

    fun onScreenTap(nx: Float, ny: Float) {
        pendingTapPosition = Pair(nx, ny)
        _uiState.update { it.copy(isCaptureRequested = true) }
    }

    fun clearTapHighlights() {
        pendingTapPosition = null
        clearEraseHistory()
        _uiState.update {
            it.copy(
                tapHighlightKeypoints = emptyList(),
                annotatedCaptureBitmap = null,
                tempCaptureBitmap = null
            )
        }
    }

    private fun computePhysicalExtent(
        depthBuffer: ByteBuffer?,
        depthW: Int, depthH: Int,
        colorW: Int, colorH: Int,
        intrinsics: FloatArray?,
        depthStride: Int = 0
    ): Pair<Float, Float>? {
        if (intrinsics == null || intrinsics.size < 2 || colorW <= 0 || colorH <= 0) {
            return null
        }

        var depthM = 2.0f

        if (depthBuffer != null && depthBuffer.capacity() > 0 && depthW > 0 && depthH > 0) {
            val stride = if (depthStride > 0) depthStride else depthW * 2
            val cx = depthW / 2
            val cy = depthH / 2
            val byteOffset = cy * stride + cx * 2

            if (byteOffset + 2 <= depthBuffer.capacity()) {
                val raw = depthBuffer.getShort(byteOffset).toInt() and 0xFFFF
                val depthMm = raw and 0x1FFF

                if (depthMm in 100..15000) {
                    depthM = depthMm / 1000f
                }
            }
        }

        val fx = intrinsics[0]
        val fy = intrinsics[1]

        val halfW = (colorW * 0.2f / fx * depthM).coerceIn(0.2f, 5f)
        val halfH = (colorH * 0.2f / fy * depthM).coerceIn(0.2f, 5f)

        return Pair(halfW, halfH)
    }

    fun setTempCapture(bitmap: Bitmap?) {
        clearEraseHistory()
        _uiState.update { it.copy(tempCaptureBitmap = bitmap) }
    }

    fun onCaptureConsumed() {
        _uiState.update { it.copy(tempCaptureBitmap = null) }
    }

    fun setInitialAnchorFromCapture() {
        val state = _uiState.value
        val originalViewMat = state.targetCaptureViewMatrix ?: return

        var flatViewMat = originalViewMat

        var anchorModelMatrix = FloatArray(16)
        android.opengl.Matrix.invertM(anchorModelMatrix, 0, originalViewMat, 0)
        // Preserve the camera-pose fallback; only overwrite if plane detection succeeds.
        val fallbackMatrix = anchorModelMatrix.copyOf()

        session?.let { s ->
            try {
                val planes = s.getAllTrackables(com.google.ar.core.Plane::class.java)

                val cameraPose = FloatArray(16)
                android.opengl.Matrix.invertM(cameraPose, 0, originalViewMat, 0)
                val camX = cameraPose[12]
                val camY = cameraPose[13]
                val camZ = cameraPose[14]
                val fwdX = -cameraPose[8]
                val fwdY = -cameraPose[9]
                val fwdZ = -cameraPose[10]

                var bestPlane: com.google.ar.core.Plane? = null
                var maxDot = -1f

                for (plane in planes) {
                    if (plane.trackingState == com.google.ar.core.TrackingState.TRACKING &&
                        plane.type == com.google.ar.core.Plane.Type.VERTICAL) {
                        val pose = plane.centerPose
                        val dx = pose.tx() - camX
                        val dy = pose.ty() - camY
                        val dz = pose.tz() - camZ
                        val len = Math.sqrt((dx*dx + dy*dy + dz*dz).toDouble()).toFloat()
                        if (len > 0.01f) {
                            val dot = (dx * fwdX + dy * fwdY + dz * fwdZ) / len
                            if (dot > maxDot) { maxDot = dot; bestPlane = plane }
                        }
                    }
                }

                bestPlane?.let { plane ->
                    val planePoseMatrix = FloatArray(16)
                    plane.centerPose.toMatrix(planePoseMatrix, 0)

                    val normalX = planePoseMatrix[4]
                    val normalY = planePoseMatrix[5]
                    val normalZ = planePoseMatrix[6]

                    val zx = normalX; val zy = normalY; val zz = normalZ
                    var xx = 1f * zz - 0f * zy
                    var xy = 0f * zx - 0f * zz
                    var xz = 0f * zy - 1f * zx
                    val xLen = Math.sqrt((xx*xx + xy*xy + xz*xz).toDouble()).toFloat()
                    if (xLen > 0.0001f) {
                        xx /= xLen; xy /= xLen; xz /= xLen
                        val yx = zy * xz - zz * xy
                        val yy = zz * xx - zx * xz
                        val yz = zx * xy - zy * xx

                        val newCamPose = FloatArray(16)
                        android.opengl.Matrix.setIdentityM(newCamPose, 0)
                        newCamPose[0] = xx; newCamPose[1] = xy; newCamPose[2] = xz
                        newCamPose[4] = yx; newCamPose[5] = yy; newCamPose[6] = yz
                        newCamPose[8] = zx; newCamPose[9] = zy; newCamPose[10] = zz
                        newCamPose[12] = camX; newCamPose[13] = camY; newCamPose[14] = camZ
                        val newViewMat = FloatArray(16)
                        android.opengl.Matrix.invertM(newViewMat, 0, newCamPose, 0)
                        flatViewMat = newViewMat

                        val wallX = planePoseMatrix[12]
                        val wallY = planePoseMatrix[13]
                        val wallZ = planePoseMatrix[14]
                        anchorModelMatrix = FloatArray(16)
                        android.opengl.Matrix.setIdentityM(anchorModelMatrix, 0)
                        anchorModelMatrix[0] = xx; anchorModelMatrix[1] = xy; anchorModelMatrix[2] = xz
                        anchorModelMatrix[4] = yx; anchorModelMatrix[5] = yy; anchorModelMatrix[6] = yz
                        anchorModelMatrix[8] = zx; anchorModelMatrix[9] = zy; anchorModelMatrix[10] = zz
                        anchorModelMatrix[12] = wallX; anchorModelMatrix[13] = wallY; anchorModelMatrix[14] = wallZ
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Plane detection failed; using camera-pose fallback")
                anchorModelMatrix = fallbackMatrix
            }
        }

        slamManager.updateAnchorTransform(anchorModelMatrix)

        _uiState.value.targetPhysicalExtent?.let { (halfW, halfH) ->
            renderer?.updateOverlayExtent(halfW, halfH)
        }

        _uiState.update {
            it.copy(
                isAnchorEstablished = true,
                targetCaptureViewMatrix = flatViewMat
            )
        }
        renderer?.anchorEstablished = true
        renderer?.hideVisualization = true
    }

    fun requestCapture() {
        slamManager.setSplatsVisible(false)
        _uiState.update { it.copy(isCaptureRequested = true, tempCaptureBitmap = null) }
    }

    fun requestExport(onCaptured: (android.graphics.Bitmap) -> Unit) {
        val r = renderer
        if (r != null) {
            r.onExportCaptured = onCaptured
            r.exportRequested = true
        } else {
            Timber.e("requestExport called but renderer is null")
        }
    }

    fun onCaptureRequestHandled() {
        _uiState.update { it.copy(isCaptureRequested = false) }
    }

    fun setUnwarpPoints(points: List<Offset>) {
        _uiState.update { it.copy(unwarpPoints = points) }
    }

    fun setActiveUnwarpPoint(index: Int) {
        _uiState.update { it.copy(activeUnwarpPointIndex = index) }
    }

    fun setMagnifierPosition(position: Offset) {
        _uiState.update { it.copy(magnifierPosition = position) }
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

    fun appendDiag(text: String) {
        _uiState.update { state ->
            val existing = state.diagLog?.lines() ?: emptyList()
            val updated = (existing + text).takeLast(20).joinToString("\n")
            state.copy(diagLog = updated)
        }
    }

    private fun setArtworkFingerprintFromComposite(bitmap: Bitmap) {
        val state = _uiState.value
        val viewMat = state.targetCaptureViewMatrix ?: return
        val depthBuffer = state.targetDepthBuffer
        if (depthBuffer == null || depthBuffer.capacity() == 0) {
            Timber.w("setArtworkFingerprintFromComposite: no depth data available; skipping feature baking")
            return
        }
        val depthW = state.targetDepthBufferWidth
        val depthH = state.targetDepthBufferHeight
        val depthStride = state.targetDepthStride
        val intrinsics = state.targetIntrinsics ?: FloatArray(0)

        viewModelScope.launch(Dispatchers.IO) {
            slamManager.setArtworkFingerprint(
                bitmap = bitmap,
                depthBuffer = depthBuffer,
                depthW = depthW,
                depthH = depthH,
                depthStride = depthStride,
                intrinsics = intrinsics,
                viewMatrix = viewMat
            )
        }
    }

    private fun computeScanHint(
        isTracking: Boolean,
        splatCount: Int,
        lightLevel: Float,
        scanPhase: ScanPhase = ScanPhase.AMBIENT,
        sectorsCovered: Int = 0
    ): String? {
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
        viewModelScope.launch { setInitialAnchorFromCapture() }
    }

    fun setPlaneConfirmationBorder(show: Boolean) {
        renderer?.showBorderForConfirmation = show
    }

    fun setVisualizationHidden(hidden: Boolean) {
        renderer?.hideVisualization = hidden
    }

    override fun onCleared() {
        super.onCleared()
        stopAutoSave()
    }
}