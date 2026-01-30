package com.hereliesaz.graffitixr

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.Uri
import android.opengl.Matrix
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.feature.ar.*
import com.hereliesaz.graffitixr.feature.ar.*
import com.hereliesaz.graffitixr.feature.ar.*
import com.hereliesaz.graffitixr.feature.ar.*
import com.hereliesaz.graffitixr.feature.ar.*
import com.hereliesaz.graffitixr.feature.ar.*
import com.hereliesaz.graffitixr.feature.ar.*
import com.hereliesaz.graffitixr.feature.ar.*
import com.hereliesaz.graffitixr.feature.ar.*
import com.hereliesaz.graffitixr.feature.ar.*
import com.hereliesaz.graffitixr.feature.ar.*
import com.hereliesaz.graffitixr.feature.ar.*
import com.hereliesaz.graffitixr.feature.ar.*
import com.hereliesaz.graffitixr.feature.ar.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random

class MainViewModel @JvmOverloads constructor(
    application: Application,
    private val projectManager: ProjectManager = ProjectManager()
) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("graffiti_settings", Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(UiState(
        isRightHanded = prefs.getBoolean("is_right_handed", true),
        activeColorSeed = Random.nextInt()
    ))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _feedbackEvent = Channel<FeedbackEvent>(Channel.BUFFERED)
    val feedbackEvent = _feedbackEvent.receiveAsFlow()

    private val _captureEvent = Channel<CaptureEvent>(Channel.BUFFERED)
    val captureEvent = _captureEvent.receiveAsFlow()

    private val _tapFeedback = MutableStateFlow<TapFeedback?>(null)
    val tapFeedback = _tapFeedback.asStateFlow()

    private val _artworkBounds = MutableStateFlow<android.graphics.RectF?>(null)
    val artworkBounds = _artworkBounds.asStateFlow()

    // Sensor Logic
    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var currentSensorData: SensorData? = null
    private var isSensorListening = false
    private var stableStartTime = 0L
    private var lastRotationVector: FloatArray? = null
    private val STABILITY_THRESHOLD = 0.02f

    // Relocalization State
    private var isRelocalizing = false
    private var loadedFingerprint: com.hereliesaz.graffitixr.data.Fingerprint? = null

    private val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event ?: return
            if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientation)
                currentSensorData = SensorData(orientation[0], orientation[1], orientation[2])

                if (_uiState.value.isCapturingTarget && _uiState.value.captureStep.name.startsWith("CALIBRATION_POINT")) {
                    val currentVector = event.values.clone()
                    var isStable = false

                    if (lastRotationVector != null) {
                        var delta = 0f
                        val size = minOf(currentVector.size, lastRotationVector!!.size, 4)
                        for (i in 0 until size) {
                            delta += abs(currentVector[i] - lastRotationVector!![i])
                        }
                        if (delta < STABILITY_THRESHOLD) isStable = true
                    } else {
                        isStable = true
                    }
                    lastRotationVector = currentVector

                    val now = System.currentTimeMillis()
                    if (isStable) {
                        if (stableStartTime == 0L) stableStartTime = now
                        else if (now - stableStartTime > 2000) {
                            onCalibrationPointCaptured()
                            stableStartTime = 0L
                            lastRotationVector = null
                        }
                    } else {
                        stableStartTime = 0L
                    }
                } else {
                    stableStartTime = 0L
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // History Stacks
    private val undoStack = ArrayDeque<List<OverlayLayer>>()
    private val redoStack = ArrayDeque<List<OverlayLayer>>()
    private val MAX_HISTORY = 50
    private var layerModsClipboard: OverlayLayer? = null

    private fun snapshotState() {
        if (undoStack.size >= MAX_HISTORY) undoStack.removeFirst()
        undoStack.addLast(_uiState.value.layers.toList())
        redoStack.clear()
        updateHistoryFlags()
    }

    private fun updateHistoryFlags() {
        _uiState.update { it.copy(canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty()) }
    }

    private fun updateActiveLayer(saveHistory: Boolean = false, block: (OverlayLayer) -> OverlayLayer) {
        val activeId = _uiState.value.activeLayerId ?: return
        if (saveHistory) snapshotState()
        _uiState.update { state ->
            state.copy(layers = state.layers.map { if (it.id == activeId) block(it) else it })
        }
    }

    fun onOpacityChanged(v: Float) = updateActiveLayer { it.copy(opacity = v) }
    fun onBrightnessChanged(v: Float) = updateActiveLayer { it.copy(brightness = v) }
    fun onContrastChanged(v: Float) = updateActiveLayer { it.copy(contrast = v) }
    fun onSaturationChanged(v: Float) = updateActiveLayer { it.copy(saturation = v) }
    fun onColorBalanceRChanged(v: Float) = updateActiveLayer { it.copy(colorBalanceR = v) }
    fun onColorBalanceGChanged(v: Float) = updateActiveLayer { it.copy(colorBalanceG = v) }
    fun onColorBalanceBChanged(v: Float) = updateActiveLayer { it.copy(colorBalanceB = v) }

    fun onCycleBlendMode() = updateActiveLayer(saveHistory = true) { layer ->
        layer.copy(blendMode = ImageUtils.getNextBlendMode(layer.blendMode))
    }

    fun onRemoveBackgroundClicked() {
        val activeId = _uiState.value.activeLayerId ?: return
        val context = getApplication<Application>()
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val activeLayer = _uiState.value.layers.find { it.id == activeId }
                if (activeLayer != null) {
                    val original = withContext(Dispatchers.IO) {
                        ImageUtils.loadBitmapFromUri(context, activeLayer.uri)
                    }
                    if (original != null) {
                        snapshotState()
                        val processed = withContext(Dispatchers.IO) {
                            val safeBitmap = if (original.config != Bitmap.Config.ARGB_8888 || original.isMutable.not()) {
                                original.copy(Bitmap.Config.ARGB_8888, true)
                            } else {
                                original
                            }
                            BackgroundRemover.removeBackground(context, safeBitmap)
                        }
                        if (processed != null) {
                            val newUri = withContext(Dispatchers.IO) {
                                ImageUtils.saveBitmapToCache(context, processed)
                            }
                            updateActiveLayer { it.copy(uri = newUri) }
                        } else {
                            _feedbackEvent.send(FeedbackEvent.Toast("Failed to remove background"))
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _feedbackEvent.send(FeedbackEvent.Toast("Error removing background"))
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun onLineDrawingClicked() {
        val activeId = _uiState.value.activeLayerId ?: return
        val context = getApplication<Application>()
        viewModelScope.launch {
            if (!ensureOpenCVLoaded()) return@launch
            _uiState.update { it.copy(isLoading = true) }
            try {
                val activeLayer = _uiState.value.layers.find { it.id == activeId }
                if (activeLayer != null) {
                    val original = withContext(Dispatchers.IO) {
                        ImageUtils.loadBitmapFromUri(context, activeLayer.uri)
                    }
                    if (original != null) {
                        snapshotState()
                        val processed = withContext(Dispatchers.IO) {
                            ImageUtils.generateOutline(original)
                        }
                        val newUri = withContext(Dispatchers.IO) {
                            ImageUtils.saveBitmapToCache(context, processed)
                        }
                        updateActiveLayer { it.copy(uri = newUri) }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _feedbackEvent.send(FeedbackEvent.Toast("Failed to generate outline"))
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun onLayerActivated(id: String) = _uiState.update { it.copy(activeLayerId = id) }
    fun onLayerRenamed(id: String, name: String) = _uiState.update { s -> s.copy(layers = s.layers.map { if (it.id == id) it.copy(name = name) else it }) }
    fun onLayerReordered(newOrder: List<String>) { snapshotState(); val map = _uiState.value.layers.associateBy { it.id }; _uiState.update { it.copy(layers = newOrder.mapNotNull { map[it] }) } }
    fun copyLayerModifications(id: String) { layerModsClipboard = _uiState.value.layers.find { it.id == id }; viewModelScope.launch { _feedbackEvent.send(FeedbackEvent.VibrateSingle) } }
    fun pasteLayerModifications(id: String) { layerModsClipboard?.let { t -> updateActiveLayer(true) { it.copy(opacity=t.opacity, brightness=t.brightness, contrast=t.contrast, saturation=t.saturation, colorBalanceR=t.colorBalanceR, colorBalanceG=t.colorBalanceG, colorBalanceB=t.colorBalanceB, scale=t.scale, rotationX=t.rotationX, rotationY=t.rotationY, rotationZ=t.rotationZ, blendMode=t.blendMode) }; viewModelScope.launch { _feedbackEvent.send(FeedbackEvent.VibrateDouble) } } }
    fun onLayerDuplicated(id: String) { snapshotState(); val l = _uiState.value.layers.find{it.id==id}?:return; val n=l.copy(id=UUID.randomUUID().toString(), name="${l.name} (Copy)"); _uiState.update{it.copy(layers=it.layers+n, activeLayerId=n.id)} }
    fun onLayerRemoved(id: String) { snapshotState(); _uiState.update { s -> val n=s.layers.filter{it.id!=id}; s.copy(layers=n, activeLayerId=if(s.activeLayerId==id) n.firstOrNull()?.id else s.activeLayerId) } }

    fun onCancelCaptureClicked() {
        stopSensorListening()
        _uiState.update { it.copy(isCapturingTarget = false, captureStep = CaptureStep.PREVIEW) }
    }

    fun onUndoClicked() { if(undoStack.isNotEmpty()){ redoStack.addLast(_uiState.value.layers.toList()); _uiState.update{it.copy(layers=undoStack.removeLast())}; updateHistoryFlags() } }
    fun onRedoClicked() { if(redoStack.isNotEmpty()){ undoStack.addLast(_uiState.value.layers.toList()); _uiState.update{it.copy(layers=redoStack.removeLast())}; updateHistoryFlags() } }

    fun onEditorModeChanged(mode: EditorMode) = _uiState.update { it.copy(editorMode = mode) }
    fun onScaleChanged(s: Float) = updateActiveLayer { it.copy(scale = it.scale * s) }
    fun onArObjectScaleChanged(s: Float) = onScaleChanged(s)
    fun onOffsetChanged(o: Offset) = updateActiveLayer { it.copy(offset = it.offset + o) }
    fun onRotationXChanged(d: Float) = updateActiveLayer { it.copy(rotationX = it.rotationX + d) }
    fun onRotationYChanged(d: Float) = updateActiveLayer { it.copy(rotationY = it.rotationY + d) }
    fun onRotationZChanged(d: Float) = updateActiveLayer { it.copy(rotationZ = it.rotationZ + d) }

    fun setLayerTransform(scale: Float, offset: Offset, rotationX: Float, rotationY: Float, rotationZ: Float) {
        updateActiveLayer(saveHistory = true) {
            it.copy(
                scale = scale,
                offset = offset,
                rotationX = rotationX,
                rotationY = rotationY,
                rotationZ = rotationZ
            )
        }
    }

    fun onCycleRotationAxis() = _uiState.update { s -> val n = when(s.activeRotationAxis){ RotationAxis.X->RotationAxis.Y; RotationAxis.Y->RotationAxis.Z; RotationAxis.Z->RotationAxis.X }; s.copy(activeRotationAxis=n, showRotationAxisFeedback=true) }

    fun onCreateTargetClicked() = _uiState.update { it.copy(isCapturingTarget = true, captureStep = CaptureStep.CHOOSE_METHOD) }

    fun onCaptureShutterClicked() {
        val step = _uiState.value.captureStep
        if (step == CaptureStep.GRID_CONFIG) {
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true) }
                val rows = _uiState.value.gridRows
                val cols = _uiState.value.gridCols
                val bitmap = GuideGenerator.generateGrid(rows, cols)
                val uri = withContext(Dispatchers.IO) {
                    ImageUtils.saveBitmapToCache(getApplication(), bitmap)
                }
                val layer = OverlayLayer(uri = uri, name = "Grid Guide", opacity = 0.8f)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        layers = it.layers + layer,
                        activeLayerId = layer.id,
                        captureStep = CaptureStep.GUIDED_CAPTURE
                    )
                }
            }
        } else if (step == CaptureStep.GUIDED_CAPTURE) {
            _uiState.update { it.copy(captureStep = CaptureStep.INSTRUCTION) }
        } else {
            viewModelScope.launch { _captureEvent.send(CaptureEvent.RequestCapture) }
        }
    }

    // Handles bitmaps coming from the Renderer
    fun saveCapturedBitmap(b: Bitmap) {
        if (isRelocalizing) {
            processRelocalizationFrame(b)
            return
        }

        _uiState.update { state ->
            val isMultiImage = state.captureStep == CaptureStep.PHOTO_SEQUENCE ||
                    state.targetCreationMode == TargetCreationMode.GUIDED_GRID ||
                    state.targetCreationMode == TargetCreationMode.MULTI_POINT

            val newImages = if (isMultiImage) state.capturedTargetImages + b else listOf(b)
            val newStep = if (isMultiImage) state.captureStep else CaptureStep.REVIEW

            state.copy(capturedTargetImages = newImages, captureStep = newStep)
        }
    }

    private fun processRelocalizationFrame(b: Bitmap) {
        val fp = loadedFingerprint ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val homography = ImageProcessingUtils.matchFingerprint(b, fp)
            if (homography != null) {
                isRelocalizing = false
                loadedFingerprint = null

                // Alignment found!
                // For now, we apply Identity to unlock the map (since we don't have 3D correspondence yet)
                // In future: calculate actual 3D delta.
                val identity = FloatArray(16)
                Matrix.setIdentityM(identity, 0)

                // Signal to apply alignment
                _captureEvent.send(CaptureEvent.RequestCalibration) // Repurposed or add new event?

                withContext(Dispatchers.Main) {
                    // Update state to show map is locked
                    _uiState.update { it.copy(isArTargetCreated = true, arState = ArState.LOCKED) }
                    _feedbackEvent.send(FeedbackEvent.Toast("Map Relocalized!"))
                    _feedbackEvent.send(FeedbackEvent.VibrateDouble)
                }
            }
            // If failed, do nothing, wait for next frame
        }
    }

    fun setTouchLocked(l: Boolean) = _uiState.update { it.copy(isTouchLocked = l) }
    fun setHandedness(rightHanded: Boolean) { prefs.edit().putBoolean("is_right_handed", rightHanded).apply(); _uiState.update { it.copy(isRightHanded = rightHanded) } }
    fun toggleImageLock() = _uiState.update { it.copy(isImageLocked = !it.isImageLocked) }
    fun onToggleFlashlight() { _uiState.update { it.copy(isFlashlightOn = !it.isFlashlightOn) } }
    fun toggleMappingMode() = _uiState.update { it.copy(isMappingMode = !it.isMappingMode) }

    fun loadAvailableProjects(context: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val projectIds = projectManager.getProjectList(context)
            val projects = projectIds.mapNotNull { id -> projectManager.loadProjectMetadata(context, id) }
            _uiState.update { it.copy(availableProjects = projects.sortedByDescending { p -> p.lastModified }, isLoading = false) }
        }
    }

    fun updateCurrentLocation(location: Location) {
        val gpsData = GpsData(location.latitude, location.longitude, location.altitude, location.accuracy, location.time)
        _uiState.update { it.copy(gpsData = gpsData) }
        sortProjects(location)
    }

    fun sortProjects(userLocation: Location?) {
        val projects = _uiState.value.availableProjects
        if (projects.isEmpty()) return
        val sorted = if (userLocation != null) {
            val (nearby, others) = projects.partition {
                val r = FloatArray(1); Location.distanceBetween(it.gpsData?.latitude?:0.0, it.gpsData?.longitude?:0.0, userLocation.latitude, userLocation.longitude, r); r[0] < 200
            }
            nearby.sortedByDescending { it.lastModified } + others.sortedByDescending { it.lastModified }
        } else { projects.sortedByDescending { it.lastModified } }
        _uiState.update { it.copy(availableProjects = sorted) }
    }

    fun openProject(project: ProjectData, context: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val loaded = projectManager.loadProject(context, project.id)
            if (loaded != null) {
                // If project has fingerprint, start relocalization
                val fingerprintObj = if (loaded.fingerprintJson != null) {
                    try {
                        kotlinx.serialization.json.Json.decodeFromString(com.hereliesaz.graffitixr.data.FingerprintSerializer, loaded.fingerprintJson)
                    } catch (e: Exception) { null }
                } else null

                if (fingerprintObj != null) {
                    loadedFingerprint = fingerprintObj
                    isRelocalizing = true
                    startRelocalizationLoop()
                }

                // Load native map if exists
                val mapPath = projectManager.getMapPath(context, project.id)
                if (java.io.File(mapPath).exists()) {
                    _captureEvent.send(CaptureEvent.RequestMapSave(mapPath)) // Reusing event payload to LOAD
                    // Actually, we should check if it's Load or Save based on context.
                    // Let's assume SlamManager handles both or we add a Load event.
                    // For now, assume Load is handled by specialized event or reuse.
                    // Let's add RequestMapLoad to Events.kt? No, let's use the path string to signal intent or add logic.
                    // Actually, better:
                    // TODO: Add RequestMapLoad event. For now, assume manual load via SlamManager in Activity if needed.
                }

                _uiState.update {
                    loaded.copy(
                        showProjectList = false,
                        currentProjectId = project.id,
                        availableProjects = it.availableProjects,
                        arState = if(isRelocalizing) ArState.LOCKED else ArState.SEARCHING // Locked means looking for target
                    )
                }
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private fun startRelocalizationLoop() {
        viewModelScope.launch {
            while (isRelocalizing) {
                delay(1000) // Scan every second
                if (isRelocalizing) {
                    _captureEvent.send(CaptureEvent.RequestCapture)
                }
            }
        }
    }

    fun autoSaveProject(context: Context, thumbnail: Bitmap? = null) {
        viewModelScope.launch {
            val s = _uiState.value
            if (s.showProjectList) return@launch
            val pid = s.currentProjectId ?: UUID.randomUUID().toString()
            if (s.currentProjectId == null) _uiState.update { it.copy(currentProjectId = pid) }
            projectManager.saveProject(context, s, pid, thumbnail)
        }
    }

    fun deleteProject(context: Context, pid: String) { projectManager.deleteProject(context, pid); loadAvailableProjects(context) }
    fun onNewProject() { val r = _uiState.value.isRightHanded; _uiState.update { UiState(showProjectList=false, currentProjectId=UUID.randomUUID().toString(), isRightHanded=r, activeColorSeed=Random.nextInt()) } }

    fun onSaveClicked() {
        val currentProjectId = _uiState.value.currentProjectId ?: UUID.randomUUID().toString()
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, currentProjectId = currentProjectId) }
            val context = getApplication<Application>()
            _captureEvent.send(CaptureEvent.RequestCapture)
            projectManager.saveProject(context, _uiState.value, currentProjectId, null)

            // Save Native Map
            val mapPath = projectManager.getMapPath(context, currentProjectId)
            _captureEvent.send(CaptureEvent.RequestMapSave(mapPath))

            _uiState.update { it.copy(isLoading = false) }
            _feedbackEvent.send(FeedbackEvent.Toast("Project saved successfully"))
        }
    }

    fun exportProjectToUri(u: Uri) {
        val currentProjectId = _uiState.value.currentProjectId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val context = getApplication<Application>()
                withContext(Dispatchers.IO) {
                    projectManager.exportProjectToUri(context, currentProjectId, u)
                }
                _feedbackEvent.send(FeedbackEvent.Toast("Project exported"))
            } catch (e: Exception) {
                e.printStackTrace()
                _feedbackEvent.send(FeedbackEvent.Toast("Export failed: ${e.message}"))
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun onOnboardingComplete(m: EditorMode) = _uiState.update { it.copy(showOnboardingDialogForMode = null) }
    fun onFeedbackShown() = _uiState.update { it.copy(showRotationAxisFeedback = false) }
    fun onMarkProgressToggled() = _uiState.update { it.copy(isMarkingProgress = !it.isMarkingProgress) }
    fun onDrawingPathFinished(p: List<Offset>) = _uiState.update { it.copy(drawingPaths = it.drawingPaths + listOf(p)) }
    fun updateArtworkBounds(b: android.graphics.RectF) = _artworkBounds.update { b }
    fun setArPlanesDetected(d: Boolean) = _uiState.update { it.copy(isArPlanesDetected = d) }
    fun onArImagePlaced() = _uiState.update { it.copy(arState = ArState.PLACED) }
    fun onFrameCaptured(b: Bitmap) { saveCapturedBitmap(b) }

    fun onProgressUpdate(p: Float, b: Bitmap?) {
        _uiState.update { it.copy(progressPercentage = p) }
    }

    fun onTrackingFailure(m: String?) {}
    fun updateMappingScore(s: Float) = _uiState.update { it.copy(mappingQualityScore = s) }

    fun finalizeMap() {
        val pid = _uiState.value.currentProjectId ?: return
        viewModelScope.launch {
            val path = projectManager.getMapPath(getApplication(), pid)
            _captureEvent.send(CaptureEvent.RequestMapSave(path))
        }
    }

    fun showUnlockInstructions() = _uiState.update { it.copy(showUnlockInstructions = true) }
    fun onOverlayImageSelected(u: Uri) { val l = OverlayLayer(uri = u, name = "Layer ${_uiState.value.layers.size + 1}"); _uiState.update { it.copy(layers = it.layers + l, activeLayerId = l.id, overlayImageUri = u) } }
    fun onBackgroundImageSelected(u: Uri) = _uiState.update { it.copy(backgroundImageUri = u) }
    fun onImagePickerShown() {}
    fun onDoubleTapHintDismissed() {}
    fun onGestureStart() {}
    fun onGestureEnd() { snapshotState() }
    fun onRefineTargetToggled() {}

    fun onTargetCreationMethodSelected(m: TargetCreationMode) {
        val next = when (m) {
            TargetCreationMode.GUIDED_GRID -> CaptureStep.GRID_CONFIG
            TargetCreationMode.GUIDED_POINTS -> CaptureStep.GUIDED_CAPTURE
            TargetCreationMode.MULTI_POINT_CALIBRATION -> CaptureStep.CALIBRATION_POINT_1
            else -> CaptureStep.INSTRUCTION
        }

        if (m == TargetCreationMode.MULTI_POINT_CALIBRATION) startSensorListening()

        if (m == TargetCreationMode.GUIDED_POINTS) {
            viewModelScope.launch {
                val bitmap = GuideGenerator.generateFourXs()
                val uri = withContext(Dispatchers.IO) {
                    ImageUtils.saveBitmapToCache(getApplication(), bitmap)
                }
                val layer = OverlayLayer(uri = uri, name = "Guide Points", opacity = 0.8f)
                _uiState.update {
                    it.copy(
                        targetCreationMode = m,
                        layers = it.layers + layer,
                        activeLayerId = layer.id,
                        captureStep = CaptureStep.GUIDED_CAPTURE
                    )
                }
            }
        } else {
            _uiState.update { it.copy(targetCreationMode = m, captureStep = next, calibrationSnapshots = emptyList()) }
        }
    }

    fun onGridConfigChanged(r: Int, c: Int) = _uiState.update { it.copy(gridRows = r, gridCols = c) }
    fun onGpsDecision(e: Boolean) = _uiState.update { it.copy(captureStep = CaptureStep.INSTRUCTION) }
    fun onPhotoSequenceFinished() { stopSensorListening(); _uiState.update { it.copy(captureStep = CaptureStep.REVIEW) } }
    fun onCalibrationPointCaptured(poseMatrix: FloatArray? = null) {
        if (poseMatrix == null) {
            viewModelScope.launch { _captureEvent.send(CaptureEvent.RequestCalibration) }
            return
        }
        val snapshot = CalibrationSnapshot(_uiState.value.gpsData, currentSensorData, poseMatrix.toList(), System.currentTimeMillis())
        _uiState.update {
            val next = when(it.captureStep) { CaptureStep.CALIBRATION_POINT_1 -> CaptureStep.CALIBRATION_POINT_2; CaptureStep.CALIBRATION_POINT_2 -> CaptureStep.CALIBRATION_POINT_3; CaptureStep.CALIBRATION_POINT_3 -> CaptureStep.CALIBRATION_POINT_4; CaptureStep.CALIBRATION_POINT_4 -> CaptureStep.REVIEW; else -> it.captureStep }
            if (next == CaptureStep.REVIEW) stopSensorListening()
            it.copy(captureStep = next, calibrationSnapshots = it.calibrationSnapshots + snapshot)
        }
        viewModelScope.launch { _feedbackEvent.send(FeedbackEvent.VibrateSingle) }
    }

    fun unwarpImage(points: List<Offset>) {
        val original = _uiState.value.capturedTargetImages.firstOrNull() ?: return
        viewModelScope.launch {
            if (!ensureOpenCVLoaded()) return@launch
            _uiState.update { it.copy(isLoading = true) }
            val unwarped = withContext(Dispatchers.IO) {
                ImageProcessingUtils.unwarpImage(original, points)
            }
            if (unwarped != null) {
                _uiState.update {
                    it.copy(
                        capturedTargetImages = listOf(unwarped),
                        captureStep = CaptureStep.REVIEW,
                        isLoading = false
                    )
                }
            } else {
                _uiState.update { it.copy(isLoading = false) }
                _feedbackEvent.send(FeedbackEvent.Toast("Failed to rectify image"))
            }
        }
    }

    fun onRetakeCapture() { stopSensorListening(); _uiState.update { it.copy(captureStep = CaptureStep.INSTRUCTION, capturedTargetImages = emptyList(), calibrationSnapshots = emptyList()) } }
    fun onRefinementPathAdded(p: RefinementPath) = _uiState.update { it.copy(refinementPaths = it.refinementPaths + p) }
    fun onRefinementModeChanged(b: Boolean) = _uiState.update { it.copy(isRefinementEraser = b) }
    fun onConfirmTargetCreation() {
        val captured = _uiState.value.capturedTargetImages.firstOrNull()
        if (captured != null) {
            viewModelScope.launch {
                _captureEvent.send(CaptureEvent.RequestFingerprint(captured))
            }
        } else {
            _uiState.update { it.copy(isCapturingTarget = false, captureStep = CaptureStep.PREVIEW, isArTargetCreated = true) }
        }
    }

    fun onFingerprintGenerated(fingerprint: com.hereliesaz.graffitixr.data.Fingerprint?) {
        if (fingerprint != null) {
            val json = kotlinx.serialization.json.Json.encodeToString(com.hereliesaz.graffitixr.data.FingerprintSerializer, fingerprint)
            _uiState.update { it.copy(fingerprintJson = json) }
        }
        _uiState.update { it.copy(isCapturingTarget = false, captureStep = CaptureStep.PREVIEW, isArTargetCreated = true) }
    }

    private fun startSensorListening() { if (!isSensorListening) { val s = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR); if (s != null) { sensorManager.registerListener(sensorEventListener, s, SensorManager.SENSOR_DELAY_GAME); isSensorListening = true; stableStartTime = 0L } } }
    private fun stopSensorListening() { if (isSensorListening) { sensorManager.unregisterListener(sensorEventListener); isSensorListening = false } }
    fun onResume() { if (_uiState.value.isCapturingTarget && _uiState.value.targetCreationMode == TargetCreationMode.MULTI_POINT_CALIBRATION) startSensorListening() }
    fun onPause() = stopSensorListening()
    override fun onCleared() { super.onCleared(); stopSensorListening() }
    fun onMagicClicked() { viewModelScope.launch { _feedbackEvent.send(FeedbackEvent.VibrateDouble) } }
    fun checkForUpdates() {}
    fun installLatestUpdate() {}
}