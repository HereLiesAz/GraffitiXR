package com.hereliesaz.graffitixr

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.data.CalibrationSnapshot
import com.hereliesaz.graffitixr.data.CaptureEvent
import com.hereliesaz.graffitixr.data.FeedbackEvent
import com.hereliesaz.graffitixr.data.GpsData
import com.hereliesaz.graffitixr.data.OverlayLayer
import com.hereliesaz.graffitixr.data.ProjectData
import com.hereliesaz.graffitixr.data.RefinementPath
import com.hereliesaz.graffitixr.data.SensorData
import com.hereliesaz.graffitixr.utils.BackgroundRemover
import com.hereliesaz.graffitixr.utils.ImageUtils
import com.hereliesaz.graffitixr.utils.ProjectManager
import com.hereliesaz.graffitixr.utils.ensureOpenCVLoaded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
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
                            viewModelScope.launch { _captureEvent.send(CaptureEvent.RequestCalibration) }
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

    fun onRemoveBackgroundClicked(context: Context) {
        val activeId = _uiState.value.activeLayerId ?: return
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

    fun onLineDrawingClicked(context: Context) {
        val activeId = _uiState.value.activeLayerId ?: return
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
    fun onCycleRotationAxis() = _uiState.update { s -> val n = when(s.activeRotationAxis){ RotationAxis.X->RotationAxis.Y; RotationAxis.Y->RotationAxis.Z; RotationAxis.Z->RotationAxis.X }; s.copy(activeRotationAxis=n, showRotationAxisFeedback=true) }

    fun onCreateTargetClicked() = _uiState.update { it.copy(isCapturingTarget = true, captureStep = CaptureStep.CHOOSE_METHOD) }

    fun onCaptureShutterClicked() {
        val step = _uiState.value.captureStep
        val mode = _uiState.value.targetCreationMode

        when (step) {
            CaptureStep.GRID_CONFIG -> {
                _uiState.update { it.copy(captureStep = CaptureStep.INSTRUCTION) }
            }
            CaptureStep.INSTRUCTION -> {
                val nextStep = when (mode) {
                    TargetCreationMode.GUIDED_GRID, TargetCreationMode.GUIDED_POINTS -> CaptureStep.GUIDED_CAPTURE
                    TargetCreationMode.RECTIFY -> CaptureStep.FRONT
                    TargetCreationMode.CAPTURE -> CaptureStep.PHOTO_SEQUENCE
                    else -> CaptureStep.PHOTO_SEQUENCE
                }
                _uiState.update { it.copy(captureStep = nextStep) }
            }
            else -> {
                viewModelScope.launch { _captureEvent.send(CaptureEvent.RequestCapture) }
            }
        }
    }

    fun saveCapturedBitmap(b: Bitmap) {
        _uiState.update { state ->
            val newImages = state.capturedTargetImages + b
            val nextStep = when (state.captureStep) {
                CaptureStep.FRONT -> CaptureStep.RECTIFY
                CaptureStep.GUIDED_CAPTURE -> CaptureStep.REVIEW
                CaptureStep.PHOTO_SEQUENCE -> CaptureStep.PHOTO_SEQUENCE 
                else -> CaptureStep.REVIEW
            }
            state.copy(capturedTargetImages = newImages, captureStep = nextStep)
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
            if (loaded != null) _uiState.update { loaded.copy(showProjectList=false, currentProjectId=project.id, availableProjects=it.availableProjects) }
            _uiState.update { it.copy(isLoading = false) }
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

    fun onSaveClicked() {}
    fun exportProjectToUri(u: Uri) {}
    fun onOnboardingComplete(m: EditorMode) = _uiState.update { it.copy(showOnboardingDialogForMode = null) }
    fun onFeedbackShown() = _uiState.update { it.copy(showRotationAxisFeedback = false) }
    fun onMarkProgressToggled() = _uiState.update { it.copy(isMarkingProgress = !it.isMarkingProgress) }
    fun onDrawingPathFinished(p: List<Offset>) = _uiState.update { it.copy(drawingPaths = it.drawingPaths + listOf(p)) }
    fun updateArtworkBounds(b: android.graphics.RectF) = _artworkBounds.update { b }
    fun setArPlanesDetected(d: Boolean) = _uiState.update { it.copy(isArPlanesDetected = d) }
    fun onArImagePlaced() = _uiState.update { it.copy(arState = ArState.PLACED) }
    fun onFrameCaptured(b: Bitmap) { saveCapturedBitmap(b) }
    fun onProgressUpdate(p: Float, b: Bitmap?) {}
    fun onTrackingFailure(m: String?) {}
    fun updateMappingScore(s: Float) = _uiState.update { it.copy(mappingQualityScore = s) }
    fun finalizeMap() {}
    fun showUnlockInstructions() = _uiState.update { it.copy(showUnlockInstructions = true) }
    
    // UPDATED: Calculate aspect ratio on load
    fun onOverlayImageSelected(u: Uri) { 
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            var ratio = 1.0f
            try {
                context.contentResolver.openInputStream(u)?.use { stream ->
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, options)
                    if (options.outWidth > 0 && options.outHeight > 0) {
                        ratio = options.outWidth.toFloat() / options.outHeight.toFloat()
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }

            val l = OverlayLayer(uri = u, name = "Layer ${_uiState.value.layers.size + 1}", aspectRatio = ratio)
            _uiState.update { it.copy(layers = it.layers + l, activeLayerId = l.id, overlayImageUri = u) } 
        }
    }
    
    fun onBackgroundImageSelected(u: Uri) = _uiState.update { it.copy(backgroundImageUri = u) }
    fun onImagePickerShown() {}
    fun onDoubleTapHintDismissed() {}
    fun onGestureStart() {}
    fun onGestureEnd() { snapshotState() }
    fun onRefineTargetToggled() {}
    fun onTargetCreationMethodSelected(m: TargetCreationMode) {
        val next = when (m) { TargetCreationMode.GUIDED_GRID -> CaptureStep.GRID_CONFIG; TargetCreationMode.MULTI_POINT_CALIBRATION -> CaptureStep.CALIBRATION_POINT_1; else -> CaptureStep.INSTRUCTION }
        if(m==TargetCreationMode.MULTI_POINT_CALIBRATION) startSensorListening()
        _uiState.update { it.copy(targetCreationMode = m, captureStep = next, calibrationSnapshots = emptyList()) }
    }
    fun onGridConfigChanged(r: Int, c: Int) = _uiState.update { it.copy(gridRows = r, gridCols = c) }
    fun onGpsDecision(e: Boolean) = _uiState.update { it.copy(captureStep = CaptureStep.INSTRUCTION) }
    fun onPhotoSequenceFinished() { stopSensorListening(); _uiState.update { it.copy(captureStep = CaptureStep.REVIEW) } }
    
    fun onCalibrationPointCaptured(poseMatrix: FloatArray? = null) {
        val snapshot = CalibrationSnapshot(
            gpsData = _uiState.value.gpsData,
            sensorData = currentSensorData,
            poseMatrix = poseMatrix?.toList(),
            timestamp = System.currentTimeMillis()
        )

        _uiState.update {
            val next = when(it.captureStep) {
                CaptureStep.CALIBRATION_POINT_1 -> CaptureStep.CALIBRATION_POINT_2
                CaptureStep.CALIBRATION_POINT_2 -> CaptureStep.CALIBRATION_POINT_3
                CaptureStep.CALIBRATION_POINT_3 -> CaptureStep.CALIBRATION_POINT_4
                CaptureStep.CALIBRATION_POINT_4 -> CaptureStep.REVIEW
                else -> it.captureStep
            }
            if (next == CaptureStep.REVIEW) stopSensorListening()
            it.copy(captureStep = next, calibrationSnapshots = it.calibrationSnapshots + snapshot)
        }
        viewModelScope.launch { _feedbackEvent.send(FeedbackEvent.VibrateSingle) }
    }
    
    fun unwarpImage(l: List<Any>) {}
    fun onRetakeCapture() { stopSensorListening(); _uiState.update { it.copy(captureStep = CaptureStep.INSTRUCTION, capturedTargetImages = emptyList(), calibrationSnapshots = emptyList()) } }
    fun onRefinementPathAdded(p: RefinementPath) = _uiState.update { it.copy(refinementPaths = it.refinementPaths + p) }
    fun onRefinementModeChanged(b: Boolean) = _uiState.update { it.copy(isRefinementEraser = b) }
    
    fun onConfirmTargetCreation(fingerprintJson: String?) {
        if (fingerprintJson != null) _uiState.update { it.copy(fingerprintJson = fingerprintJson) }
        _uiState.update { it.copy(isCapturingTarget = false, captureStep = CaptureStep.PREVIEW, isArTargetCreated = true) }
    }

    private fun startSensorListening() {
        if (!isSensorListening) {
            val s = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (s != null) { sensorManager.registerListener(sensorEventListener, s, SensorManager.SENSOR_DELAY_GAME); isSensorListening = true; stableStartTime = 0L }
        }
    }
    private fun stopSensorListening() { if (isSensorListening) { sensorManager.unregisterListener(sensorEventListener); isSensorListening = false } }

    fun onResume() { if (_uiState.value.isCapturingTarget && _uiState.value.targetCreationMode == TargetCreationMode.MULTI_POINT_CALIBRATION) startSensorListening() }
    fun onPause() = stopSensorListening()
    override fun onCleared() { super.onCleared(); stopSensorListening() }
    fun onMagicClicked() { viewModelScope.launch { _feedbackEvent.send(FeedbackEvent.VibrateDouble) } }
    fun checkForUpdates() {}
    fun installLatestUpdate() {}
}
