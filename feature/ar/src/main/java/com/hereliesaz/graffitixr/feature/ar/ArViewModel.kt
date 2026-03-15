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
import com.google.ar.core.exceptions.CameraNotAvailableException
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private val isSaving = AtomicBoolean(false)
    private var lastSavedSplatCount = 0
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
                if (project?.id != loadedProjectId) {
                    loadedProjectId = null
                }
            }
        }

        viewModelScope.launch {
            settingsRepository.arScanMode.collect { mode ->
                _uiState.update { it.copy(arScanMode = mode) }
                renderer?.scanMode = mode
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
    }

    fun setArScanMode(mode: ArScanMode) {
        viewModelScope.launch { settingsRepository.setArScanMode(mode) }
    }

    fun setShowAnchorBoundary(show: Boolean) {
        viewModelScope.launch { settingsRepository.setShowAnchorBoundary(show) }
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
        saveMapNow()
        saveCloudPointsNow()
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

    private suspend fun saveMapBlocking() {
        val project = projectRepository.currentProject.value ?: return
        if (slamManager.getSplatCount() <= 0) return
        withContext(Dispatchers.IO) {
            try {
                val root = File(appContext.filesDir, "projects/${project.id}")
                if (!root.exists()) root.mkdirs()
                slamManager.saveModel(File(root, "map.bin").absolutePath)
                lastSavedSplatCount = slamManager.getSplatCount()
                loadedProjectId = project.id
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private suspend fun saveCloudPointsBlocking() {
        val project = projectRepository.currentProject.value ?: return
        if (_uiState.value.arScanMode != ArScanMode.CLOUD_POINTS) return
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
                slamManager.saveModel(File(root, "map.bin").absolutePath)
                lastSavedSplatCount = slamManager.getSplatCount()
                loadedProjectId = project.id
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isSaving.set(false)
            }
        }
    }

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

    private fun loadCloudPointsIfExists() {
        val project = projectRepository.currentProject.value ?: return
        if (_uiState.value.arScanMode != com.hereliesaz.graffitixr.common.model.ArScanMode.CLOUD_POINTS) return

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
                lastSavedSplatCount = slamManager.getSplatCount()
                loadedProjectId = project.id
            }
        }
    }

    private fun startAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(10_000)
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
            arRenderer.anchorEstablished = _uiState.value.isAnchorEstablished
            if (session != null && isSessionResumed) {
                arRenderer.attachSession(session)
            }
        }
    }

    fun setTrackingState(isTracking: Boolean, splatCount: Int, isDepthApiSupported: Boolean, cameraYaw: Float = 0f) {
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
                )
            )
        }
    }

    fun updatePaintingGuide(composite: android.graphics.Bitmap) {
        addLayerFeaturesToSLAM(composite)
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
        val extent = computePhysicalExtent(depthBuffer, depthBufW, depthBufH, colorW, colorH, intrinsics)

        if (tapPos != null) {
            val rotatedBmp = if (displayRotation != 0) {
                val matrix = android.graphics.Matrix().apply { postRotate(displayRotation.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else bitmap

            pendingTapPosition = null
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
                    isCaptureRequested = false
                )
            }
            viewModelScope.launch(Dispatchers.Default) {
                val maskedBmp = rotatedBmp.isolateMarkings()
                _uiState.update { it.copy(tempCaptureBitmap = maskedBmp, annotatedCaptureBitmap = null) }
            }
            return
        }

        val rotatedBmp = if (displayRotation != 0) {
            val matrix = android.graphics.Matrix().apply { postRotate(displayRotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else bitmap

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
        intrinsics: FloatArray?
    ): Pair<Float, Float>? {
        return Pair(0.2f, 0.2f)
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
                e.printStackTrace()
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
    }

    fun requestCapture() {
        _uiState.update { it.copy(isCaptureRequested = true) }
    }

    fun captureKeyframe() {
        requestCapture()
    }

    fun onCaptureRequestHandled() {
        _uiState.update { it.copy(isCaptureRequested = false) }
    }

    fun restoreSplats() {}

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
        _uiState.update { it.copy(diagLog = text) }
    }

    private fun addLayerFeaturesToSLAM(bitmap: Bitmap) {
        val state = _uiState.value
        val viewMat = state.targetCaptureViewMatrix ?: return
        val depthBuffer = state.targetDepthBuffer ?: ByteBuffer.allocateDirect(0)
        val depthW = state.targetDepthBufferWidth ?: 0
        val depthH = state.targetDepthBufferHeight ?: 0
        val depthStride = state.targetDepthStride ?: 0
        val intrinsics = state.targetIntrinsics ?: FloatArray(0)

        viewModelScope.launch(Dispatchers.IO) {
            slamManager.addLayerFeatures(
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
        if (!isTracking) return "Move device slowly to recover tracking"
        return when (scanPhase) {
            ScanPhase.AMBIENT -> "Slowly rotate 360° to map your surroundings (${sectorsCovered * 30}°)"
            ScanPhase.WALL -> {
                if (lightLevel < 0.3f) "Too dark. Turn on the flashlight."
                else if (splatCount < 5000) "Walk left and right to build map"
                else if (splatCount < 20000) "Move closer to the wall"
                else if (splatCount < 40000) "Scan higher and lower"
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

    override fun onCleared() {
        super.onCleared()
        stopAutoSave()
    }
}