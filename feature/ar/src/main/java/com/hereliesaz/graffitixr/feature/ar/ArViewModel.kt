// FILE: app/src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt
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

    @Volatile
    private var pendingTapPosition: Pair<Float, Float>? = null

    init {
        viewModelScope.launch {
            projectRepository.currentProject.collect { project ->
                _uiState.update { it.copy(isAnchorEstablished = project?.fingerprint != null) }
            }
        }

        viewModelScope.launch {
            settingsRepository.arScanMode.collect { mode ->
                _uiState.update { it.copy(arScanMode = mode) }
                renderer?.scanMode = mode
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
        saveMapNow()
        saveCloudPointsNow()
    }

    private suspend fun performFullCleanupLocked() {
        renderer?.attachSession(null)
        if (isSessionResumed) {
            try {
                session?.pause()
            } catch (e: Exception) {}
            isSessionResumed = false
        }
        delay(150)
        val s = session
        session = null
        s?.let {
            try {
                it.close()
            } catch (e: Exception) {}
        }
        _isCameraInUseByAr.value = false
        slamManager.setRelocEnabled(false)
    }

    private fun saveMapNow() {
        val project = projectRepository.currentProject.value ?: return
        if (isSaving.getAndSet(true)) return

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
        if (slamManager.getSplatCount() > 0) return

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
        val progress = if (isTracking) slamManager.getPaintingProgress() else _uiState.value.paintingProgress
        _uiState.update {
            it.copy(
                isScanning = isTracking,
                splatCount = splatCount,
                isDepthApiSupported = isDepthApiSupported,
                paintingProgress = progress,
                scanHint = computeScanHint(
                    isTracking = isTracking,
                    splatCount = splatCount,
                    lightLevel = it.lightLevel
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

            viewModelScope.launch(Dispatchers.Default) {
                val maskedBmp = rotatedBmp.isolateMarkings()

                _uiState.update {
                    it.copy(
                        tempCaptureBitmap = maskedBmp,
                        annotatedCaptureBitmap = null,
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

    fun eraseMaskedMark(nx: Float, ny: Float) {
        val currentBmp = _uiState.value.tempCaptureBitmap ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val updated = currentBmp.eraseColorBlob(nx, ny)
            _uiState.update { it.copy(tempCaptureBitmap = updated) }
        }
    }

    fun onScreenTap(nx: Float, ny: Float) {
        pendingTapPosition = Pair(nx, ny)
        _uiState.update { it.copy(isCaptureRequested = true) }
    }

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

    private fun computePhysicalExtent(
        depthBuffer: ByteBuffer?,
        depthW: Int, depthH: Int,
        colorW: Int, colorH: Int,
        intrinsics: FloatArray?
    ): Pair<Float, Float>? {
        // Scaled down to an unobtrusive 20 centimeters by default.
        return Pair(0.2f, 0.2f)
    }

    fun setTempCapture(bitmap: Bitmap?) {
        _uiState.update { it.copy(tempCaptureBitmap = bitmap) }
    }

    fun onCaptureConsumed() {
        _uiState.update { it.copy(tempCaptureBitmap = null) }
    }

    fun setInitialAnchorFromCapture() {
        val state = _uiState.value
        val originalViewMat = state.targetCaptureViewMatrix ?: return

        var flatViewMat = originalViewMat

        session?.let { s ->
            try {
                // Interrogate the mesh for its most prominent vertical plane
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
                            if (dot > maxDot) {
                                maxDot = dot
                                bestPlane = plane
                            }
                        }
                    }
                }

                bestPlane?.let { plane ->
                    // Extract the absolute normal of the plane we're staring at.
                    val planePoseMatrix = FloatArray(16)
                    plane.centerPose.toMatrix(planePoseMatrix, 0)

                    val normalX = planePoseMatrix[4]
                    val normalY = planePoseMatrix[5]
                    val normalZ = planePoseMatrix[6]

                    // We forcefully align the camera's Z axis to perfectly oppose the plane's normal,
                    // guaranteeing the 2D image lays flush against physical reality.
                    val zx = normalX
                    val zy = normalY
                    val zz = normalZ

                    val ux = 0f
                    val uy = 1f
                    val uz = 0f

                    var xx = uy * zz - uz * zy
                    var xy = uz * zx - ux * zz
                    var xz = ux * zy - uy * zx

                    val xLen = Math.sqrt((xx*xx + xy*xy + xz*xz).toDouble()).toFloat()
                    if (xLen > 0.0001f) {
                        xx /= xLen
                        xy /= xLen
                        xz /= xLen

                        val yx = zy * xz - zz * xy
                        val yy = zz * xx - zx * xz
                        val yz = zx * xy - zy * xx

                        val newCamPose = FloatArray(16)
                        android.opengl.Matrix.setIdentityM(newCamPose, 0)
                        newCamPose[0] = xx
                        newCamPose[1] = xy
                        newCamPose[2] = xz

                        newCamPose[4] = yx
                        newCamPose[5] = yy
                        newCamPose[6] = yz

                        newCamPose[8] = zx
                        newCamPose[9] = zy
                        newCamPose[10] = zz

                        newCamPose[12] = camX
                        newCamPose[13] = camY
                        newCamPose[14] = camZ

                        val newViewMat = FloatArray(16)
                        android.opengl.Matrix.invertM(newViewMat, 0, newCamPose, 0)
                        flatViewMat = newViewMat
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        _uiState.update {
            it.copy(
                isAnchorEstablished = true,
                targetCaptureViewMatrix = flatViewMat
            )
        }
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

    private fun computeScanHint(isTracking: Boolean, splatCount: Int, lightLevel: Float): String? {
        if (!isTracking) return "Move device slowly to recover tracking"
        if (lightLevel < 0.3f) return "Too dark. Turn on the flashlight."
        if (splatCount < 5000) return "Walk left and right to build map"
        if (splatCount < 20000) return "Move closer to the wall"
        if (splatCount < 40000) return "Scan higher and lower"
        return null
    }

    override fun onCleared() {
        super.onCleared()
        stopAutoSave()
    }
}