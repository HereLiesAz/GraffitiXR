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
import com.hereliesaz.graffitixr.common.model.MuralMethod
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.ScanPhase
import com.hereliesaz.graffitixr.common.util.isolateMarkings
import com.hereliesaz.graffitixr.common.util.eraseColorBlob
import com.hereliesaz.graffitixr.core.collaboration.CollaborationManager
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
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import timber.log.Timber
import javax.inject.Inject
import androidx.core.net.toUri

@HiltViewModel
class ArViewModel @Inject constructor(
    private val slamManager: SlamManager,
    private val stereoProvider: StereoDepthProvider,
    private val projectRepository: ProjectRepository,
    private val settingsRepository: SettingsRepository,
    private val projectManager: com.hereliesaz.graffitixr.data.ProjectManager,
    @param:ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArUiState())
    val uiState: StateFlow<ArUiState> = _uiState.asStateFlow()

    private val _unfreezeRequested = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val unfreezeRequested: SharedFlow<Unit> = _unfreezeRequested.asSharedFlow()

    private var session: Session? = null
    private var renderer: ArRenderer? = null

    private var collaborationManager: CollaborationManager? = null

    fun startCollaborationHost() {
        if (!uiState.value.isAnchorEstablished || uiState.value.splatCount == 0) {
            _uiState.update { it.copy(coopStatus = "Capture a target first to host.", showCoopNotFoundDialog = false) }
            return
        }
        
        if (collaborationManager == null) {
            collaborationManager = CollaborationManager(appContext)
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, coopStatus = "Hosting session...", coopRole = com.hereliesaz.graffitixr.common.model.CoopRole.HOST, showCoopNotFoundDialog = false) }
            val projectId = loadedProjectId ?: return@launch
            val projectFile = File(appContext.cacheDir, "coop_project.gxr")
            projectManager.exportProjectToUri(appContext, projectId, projectFile.toUri())
            
            collaborationManager?.startServer(projectFile)
        }
    }

    fun startCollaborationDiscovery() {
        if (collaborationManager == null) {
            collaborationManager = CollaborationManager(appContext)
        }
        _uiState.update { it.copy(isCoopSearching = true, coopStatus = "Searching for sessions...", showCoopNotFoundDialog = false) }
        
        var found = false
        val searchTimeout = viewModelScope.launch {
            delay(5000)
            if (!found) {
                _uiState.update { it.copy(isCoopSearching = false, coopStatus = null, showCoopNotFoundDialog = true) }
            }
        }

        collaborationManager?.startDiscovery { address, port ->
            if (found) return@startDiscovery
            found = true
            searchTimeout.cancel()
            
            viewModelScope.launch {
                _uiState.update { it.copy(isCoopSearching = false, coopStatus = "Joining session...") }
                val projectFile = File(appContext.filesDir, "imported_coop_${System.currentTimeMillis()}.gxr")
                collaborationManager?.connectToPeer(address, port, projectFile)
                
                // Load the received project
                val project = projectManager.importProjectFromUri(appContext, projectFile.toUri())
                if (project != null) {
                    projectRepository.loadProject(project.id)
                    _uiState.update { it.copy(isSyncing = false, coopStatus = "Joined!", coopRole = com.hereliesaz.graffitixr.common.model.CoopRole.GUEST) }
                } else {
                    _uiState.update { it.copy(isSyncing = false, coopStatus = "Failed to join.", coopRole = com.hereliesaz.graffitixr.common.model.CoopRole.NONE) }
                }
            }
        }
    }

    fun dismissCoopNotFoundDialog() {
        _uiState.update { it.copy(showCoopNotFoundDialog = false) }
    }

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
                if (project != null) {
                    loadedProjectId = project.id
                    loadMapIfExists()
                    loadCloudPointsIfExists()
                    loadFingerprintIfExists()
                }
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
        isInArMode = enabled
        updateSessionStateLocked(context)
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
            
            if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                config.depthMode = Config.DepthMode.AUTOMATIC
                _uiState.update { it.copy(isDepthApiSupported = true) }
            }

            s.configure(config)

            // Task: Engage dual lens depth mapping if device is capable.
            // ARCore 1.53 uses setStereoCameraUsage on the CameraConfigFilter.
            val filter = CameraConfigFilter(s).apply {
                targetFps = EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30)
                try {
                    // Filter for configs that support stereo camera usage
                    setStereoCameraUsage(EnumSet.of(CameraConfig.StereoCameraUsage.REQUIRE_AND_USE))
                } catch (e: NoSuchMethodError) {
                    Timber.w("StereoCameraUsage not supported on this ARCore version")
                }
            }
            
            val cameraConfigs = s.getSupportedCameraConfigs(filter)
            if (cameraConfigs.isNotEmpty()) {
                s.cameraConfig = cameraConfigs[0]
                _uiState.update { it.copy(isDualLensActive = true) }
            } else {
                // Fallback to standard mono config if stereo isn't available
                val fallbackFilter = CameraConfigFilter(s).apply {
                    targetFps = EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30)
                }
                val fallbackConfigs = s.getSupportedCameraConfigs(fallbackFilter)
                if (fallbackConfigs.isNotEmpty()) {
                    s.cameraConfig = fallbackConfigs[0]
                }
            }

            session = s
            _isCameraInUseByAr.value = true
            
            // Critical: if the renderer is already attached, update it with the new session
            renderer?.attachSession(s)
        } catch (e: Exception) {
            Timber.e(e, "Failed to create ARCore session")
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
            session?.let {
                if (isSessionResumed) it.pause()
                it.close()
            }
            session = null
            renderer = null
            isSessionResumed = false
            _isCameraInUseByAr.value = false
            isDestroying = false
        }
    }

    fun saveMapBlocking() {
        val projectId = loadedProjectId ?: return
        val path = projectManager.getMapPath(appContext, projectId)
        slamManager.saveModel(path)
        lastSavedSplatCount.set(slamManager.getSplatCount())
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
        val path = projectManager.getMapPath(appContext, projectId)
        if (File(path).exists()) {
            if (projectRepository.currentProject.value?.id == loadedProjectId && slamManager.getSplatCount() > 0) return
            
            viewModelScope.launch(Dispatchers.IO) {
                slamManager.loadModel(path)
                lastSavedSplatCount.set(slamManager.getSplatCount())
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
        renderer = r
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
        centerDepth: Float = -1f,
        visConf: Float = 0f,
        globConf: Float = 0f
    ) {
        val progress = if (isTracking) slamManager.getPaintingProgress() else _uiState.value.paintingProgress

        val sector = (((cameraYaw % 360f) + 360f) % 360f / 30f).toInt().coerceIn(0, 11)
        visitedSectors[sector] = true
        val sectorsCovered = visitedSectors.count { it }

        _uiState.update { state ->
            val newPhase = when (state.scanPhase) {
                ScanPhase.AMBIENT -> if (sectorsCovered >= 6) ScanPhase.WALL else ScanPhase.AMBIENT
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
                ambientSectorsCovered = sectorsCovered,
                scanHint = computeScanHint(
                    isTracking = isTracking,
                    splatCount = splatCount,
                    lightLevel = state.lightLevel,
                    scanPhase = newPhase,
                    sectorsCovered = sectorsCovered
                ),
                distanceToAnchorMeters = distanceToAnchorMeters,
                anchorRelativeDirection = anchorRelativeDirection,
                isDualLensActive = isDualLens,
                currentCenterDepth = centerDepth,
                visibleSplatConfidenceAvg = visConf,
                globalSplatConfidenceAvg = globConf
            )
        }
    }

    fun updatePaintingGuide(bitmap: Bitmap) {
        // Future: feed to PnP engine if needed
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
                    unwarpPoints = initialPoints
                )
            }
            detectTargetKeypoints(rotatedBmp)
            return
        }

        extent?.let { (halfW, halfH) ->
            renderer?.updateOverlayExtent(halfW, halfH)
        }

        _uiState.update {
            it.copy(
                tempCaptureBitmap = rotatedBmp,
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
        detectTargetKeypoints(rotatedBmp)
    }

    private fun detectTargetKeypoints(bitmap: Bitmap) {
        viewModelScope.launch {
            val kps = withContext(Dispatchers.Default) {
                slamManager.getKeypoints(bitmap).map { Offset(it.first, it.second) }
            }
            val mask = withContext(Dispatchers.Default) {
                generateInitialMask(bitmap.width, bitmap.height, kps)
            }
            _uiState.update { it.copy(tempCaptureBitmap = bitmap, annotatedCaptureBitmap = mask) }
        }
    }


    private fun generateInitialMask(w: Int, h: Int, kps: List<Offset>): Bitmap {
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(mask)
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.CYAN // Use Cyan for a premium look
            alpha = 180
            style = android.graphics.Paint.Style.FILL
        }
        for (kp in kps) {
            canvas.drawCircle(kp.x * w, kp.y * h, 8f, paint)
        }
        return mask
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
        }
    }


    fun onScreenTap(nx: Float, ny: Float) {
        pendingTapPosition = nx to ny
        requestCapture()
    }

    fun clearTapHighlights() {
        _uiState.update { it.copy(tapHighlightKeypoints = emptyList()) }
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
    }




    fun onCaptureConsumed() {
        _uiState.update { it.copy(tempCaptureBitmap = null, annotatedCaptureBitmap = null) }
    }

    fun setInitialAnchorFromCapture() {
        val s = session ?: return
        val frame = s.update()
        val camera = frame.camera
        
        val viewMat = FloatArray(16)
        camera.getViewMatrix(viewMat, 0)
        val flatViewMat = viewMat.clone()

        val projMat = FloatArray(16)
        camera.getProjectionMatrix(projMat, 0, 0.1f, 100.0f)

        val hitX = 0.5f; val hitY = 0.5f
        val hits = frame.hitTest(hitX * appContext.resources.displayMetrics.widthPixels, hitY * appContext.resources.displayMetrics.heightPixels)
        
        var anchorModelMatrix = FloatArray(16)
        android.opengl.Matrix.setIdentityM(anchorModelMatrix, 0)

        val fallbackMatrix = FloatArray(16)
        android.opengl.Matrix.invertM(fallbackMatrix, 0, viewMat, 0)
        fallbackMatrix[12] += -fallbackMatrix[8] * 2.0f
        fallbackMatrix[13] += -fallbackMatrix[9] * 2.0f
        fallbackMatrix[14] += -fallbackMatrix[10] * 2.0f

        if (hits.isEmpty()) {
            anchorModelMatrix = fallbackMatrix
        } else {
            try {
                val pose = hits[0].hitPose
                pose.toMatrix(anchorModelMatrix, 0)
                
                val camPose = camera.pose
                val dx = pose.tx() - camPose.tx()
                val dy = pose.ty() - camPose.ty()
                val dz = pose.tz() - camPose.tz()
                val dist = Math.sqrt((dx*dx + dy*dy + dz*dz).toDouble())
                
                if (dist < 0.1 || dist > 10.0) {
                    anchorModelMatrix = fallbackMatrix
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
                targetCaptureViewMatrix = flatViewMat,
                scanPhase = ScanPhase.COMPLETE
            )
        }
        renderer?.anchorEstablished = true
        renderer?.hideVisualization = true
    }

    fun requestCapture() {
        slamManager.setSplatsVisible(false)
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
        destroyArSession()
    }
}
