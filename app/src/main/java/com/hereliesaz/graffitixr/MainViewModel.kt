package com.hereliesaz.graffitixr

import com.hereliesaz.graffitixr.natives.ensureOpenCVLoaded
import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.feature.ar.*
import com.hereliesaz.graffitixr.common.model.*
import com.hereliesaz.graffitixr.common.model.*
import com.hereliesaz.graffitixr.common.model.RotationAxis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application), EditorActions {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _feedbackEvent = MutableSharedFlow<FeedbackEvent>()
    val feedbackEvent: SharedFlow<FeedbackEvent> = _feedbackEvent.asSharedFlow()

    // Reference to Renderer (set by Activity)
    var arRenderer: ArRenderer? = null

    // Project Logic
    private var currentFingerprint: Bitmap? = null

    fun showTapFeedback(position: Offset, isSuccess: Boolean) {
        viewModelScope.launch {
            val feedback = if (isSuccess) TapFeedback.Success(position) else TapFeedback.Failure(position)
            _uiState.update { it.copy(tapFeedback = feedback) }
            kotlinx.coroutines.delay(500)
            _uiState.update { it.copy(tapFeedback = null) }
        }
    }

    fun togglePointCloud() {
        _uiState.update { it.copy(showPointCloud = !it.showPointCloud) }
        arRenderer?.showPointCloud = _uiState.value.showPointCloud
    }

    fun toggleFlashlight() {
        _uiState.update { it.copy(isFlashlightOn = !it.isFlashlightOn) }
        // Flashlight logic handled in Activity via session config usually
    }

    // --- EditorActions Implementation ---

    override fun onOpacityChanged(v: Float) {
        updateActiveLayer { it.copy(opacity = v) }
    }

    override fun onBrightnessChanged(v: Float) {
        // Todo: Implement filters
    }

    override fun onContrastChanged(v: Float) {
        // Todo: Implement filters
    }

    override fun onSaturationChanged(v: Float) {
        // Todo: Implement filters
    }

    override fun onColorBalanceRChanged(v: Float) { }
    override fun onColorBalanceGChanged(v: Float) { }
    override fun onColorBalanceBChanged(v: Float) { }

    override fun onUndoClicked() {
        viewModelScope.launch { _feedbackEvent.emit(FeedbackEvent.Toast("Undo not implemented")) }
    }

    override fun onRedoClicked() {
        viewModelScope.launch { _feedbackEvent.emit(FeedbackEvent.Toast("Redo not implemented")) }
    }

    override fun onMagicClicked() {
        viewModelScope.launch {
            _feedbackEvent.emit(FeedbackEvent.VibrateDouble)
            // TODO: Trigger actual alignment logic here
        }
    }

    override fun onRemoveBackgroundClicked() {
        // Todo: Call python/OpenCV grabcut or similar
    }

    override fun onLineDrawingClicked() {
        _uiState.update { it.copy(editorMode = EditorMode.DRAW) }
    }

    override fun onCycleBlendMode() {
        // Todo: implementation
    }

    override fun toggleImageLock() {
        // Check AR State, if locked, unlock, etc.
        val newState = if (_uiState.value.arState == ArState.LOCKED) ArState.IDLE else ArState.LOCKED
        _uiState.update { it.copy(arState = newState) }
    }

    override fun onLayerActivated(id: String) {
        _uiState.update { it.copy(activeLayerId = id) }
    }

    override fun onLayerRenamed(id: String, name: String) {
        val layers = _uiState.value.layers.map {
            if (it.id == id) it.copy(name = name) else it
        }
        _uiState.update { it.copy(layers = layers) }
    }

    override fun onLayerReordered(newOrder: List<String>) {
        // Reorder logic
    }

    override fun onLayerDuplicated(id: String) {
        val layer = _uiState.value.layers.find { it.id == id } ?: return
        val newLayer = layer.copy(id = UUID.randomUUID().toString(), name = layer.name + " Copy")
        val layers = _uiState.value.layers + newLayer
        _uiState.update { it.copy(layers = layers, activeLayerId = newLayer.id) }
        updateRenderer()
    }

    override fun onLayerRemoved(id: String) {
        val layers = _uiState.value.layers.filterNot { it.id == id }
        _uiState.update { it.copy(layers = layers, activeLayerId = null) }
        updateRenderer()
    }

    override fun copyLayerModifications(id: String) { }
    override fun pasteLayerModifications(id: String) { }

    override fun onScaleChanged(s: Float) {
        updateActiveLayer { it.copy(scale = it.scale * s) }
    }

    override fun onOffsetChanged(o: Offset) {
        updateActiveLayer { it.copy(x = it.x + o.x, y = it.y + o.y) }
    }

    override fun onRotationXChanged(d: Float) { }
    override fun onRotationYChanged(d: Float) { }
    override fun onRotationZChanged(d: Float) {
        updateActiveLayer { it.copy(rotation = it.rotation + d) }
    }

    override fun onCycleRotationAxis() { }

    override fun onGestureStart() {
        _uiState.update { it.copy(gestureInProgress = true) }
    }

    override fun onGestureEnd() {
        _uiState.update { it.copy(gestureInProgress = false) }
    }

    override fun setLayerTransform(scale: Float, offset: Offset, rx: Float, ry: Float, rz: Float) {
        updateActiveLayer { it.copy(scale = scale, x = offset.x, y = offset.y, rotation = rz) }
    }

    override fun onFeedbackShown() { }
    override fun onDoubleTapHintDismissed() { }
    override fun onOnboardingComplete(mode: Any) { }
    override fun onDrawingPathFinished(path: List<Offset>) { }

    // Helpers
    private fun updateActiveLayer(transform: (OverlayLayer) -> OverlayLayer) {
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
            val loadedProject = projectManager.loadProject(context, project.id)
            if (loadedProject != null) {
                val data = loadedProject.projectData

                // If project has fingerprint, start relocalization
                val fingerprintObj = data.fingerprint

                if (fingerprintObj != null) {
                    loadedFingerprint = fingerprintObj
                    isRelocalizing = true
                    startRelocalizationLoop()
                }

                // Load native map if exists
                val mapPath = projectManager.getMapPath(context, project.id)
                if (java.io.File(mapPath).exists()) {
                    _captureEvent.send(CaptureEvent.RequestMapSave(mapPath))
                }

                // Reconstruct UiState from LoadedProject
                val fingerprintJson = if (fingerprintObj != null) {
                    kotlinx.serialization.json.Json.encodeToString(com.hereliesaz.graffitixr.data.FingerprintSerializer, fingerprintObj)
                } else null

                val drawingPaths = data.drawingPaths.map { path ->
                    path.map { pair -> Offset(pair.first, pair.second) }
                }

                val loadedUiState = UiState(
                    backgroundImageUri = data.backgroundImageUri,
                    overlayImageUri = data.overlayImageUri,
                    capturedTargetImages = loadedProject.targetImages,
                    capturedTargetUris = data.targetImageUris,
                    refinementPaths = data.refinementPaths,
                    drawingPaths = drawingPaths,
                    progressPercentage = data.progressPercentage,
                    fingerprintJson = fingerprintJson,
                    layers = data.layers,
                    calibrationSnapshots = data.calibrationSnapshots,
                    activeLayerId = if (data.layers.isNotEmpty()) data.layers.first().id else null
                ).let { state ->
                     if (state.layers.isEmpty() && data.overlayImageUri != null) {
                         // Migration logic
                         val legacyLayer = OverlayLayer(
                             uri = data.overlayImageUri!!,
                             name = "Base Layer",
                             opacity = data.opacity,
                             brightness = data.brightness,
                             contrast = data.contrast,
                             saturation = data.saturation,
                             colorBalanceR = data.colorBalanceR,
                             colorBalanceG = data.colorBalanceG,
                             colorBalanceB = data.colorBalanceB,
                             scale = data.scale,
                             rotationX = data.rotationX,
                             rotationY = data.rotationY,
                             rotationZ = data.rotationZ,
                             offset = data.offset,
                             blendMode = data.blendMode
                         )
                         state.copy(layers = listOf(legacyLayer), activeLayerId = legacyLayer.id)
                     } else {
                         state
                     }
                }

                _uiState.update {
                    loadedUiState.copy(
                        showProjectList = false,
                        currentProjectId = project.id,
                        availableProjects = it.availableProjects,
                        arState = if(isRelocalizing) ArState.LOCKED else ArState.SEARCHING
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
            val projectData = createProjectDataFromState(s, pid)
            projectManager.saveProject(context, projectData, s.capturedTargetImages, thumbnail)
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
            val projectData = createProjectDataFromState(_uiState.value, currentProjectId)
            projectManager.saveProject(context, projectData, _uiState.value.capturedTargetImages, null)

            // Save Native Map
            val mapPath = projectManager.getMapPath(context, currentProjectId)
            _captureEvent.send(CaptureEvent.RequestMapSave(mapPath))

            _uiState.update { it.copy(isLoading = false) }
            _feedbackEvent.send(FeedbackEvent.Toast("Project saved successfully"))
        }
    }

    private fun createProjectDataFromState(state: UiState, projectId: String): ProjectData {
        val activeLayer = state.activeLayer

        // Deserialize Fingerprint JSON string to Object (if exists)
        val fingerprintObj: com.hereliesaz.graffitixr.data.Fingerprint? = state.fingerprintJson?.let {
            try {
                kotlinx.serialization.json.Json.decodeFromString(com.hereliesaz.graffitixr.data.FingerprintSerializer, it)
            } catch (e: Exception) {
                null
            }
        }

        // Map List<List<Offset>> to List<List<Pair<Float, Float>>>
        val serializableDrawingPaths = state.drawingPaths.map { path ->
            path.map { offset -> Pair(offset.x, offset.y) }
        }

        return ProjectData(
            id = projectId,
            name = projectId,
            lastModified = System.currentTimeMillis(),
            backgroundImageUri = state.backgroundImageUri,
            overlayImageUri = state.overlayImageUri,
            thumbnailUri = null, // Handled by ProjectManager
            targetImageUris = state.capturedTargetUris, // Updated by ProjectManager
            refinementPaths = state.refinementPaths,
            gpsData = state.gpsData,
            opacity = activeLayer?.opacity ?: 1f,
            brightness = activeLayer?.brightness ?: 0f,
            contrast = activeLayer?.contrast ?: 1f,
            saturation = activeLayer?.saturation ?: 1f,
            colorBalanceR = activeLayer?.colorBalanceR ?: 1f,
            colorBalanceG = activeLayer?.colorBalanceG ?: 1f,
            colorBalanceB = activeLayer?.colorBalanceB ?: 1f,
            scale = activeLayer?.scale ?: 1f,
            rotationX = activeLayer?.rotationX ?: 0f,
            rotationY = activeLayer?.rotationY ?: 0f,
            rotationZ = activeLayer?.rotationZ ?: 0f,
            offset = activeLayer?.offset ?: Offset.Zero,
            blendMode = activeLayer?.blendMode ?: androidx.compose.ui.graphics.BlendMode.SrcOver,
            fingerprint = fingerprintObj,
            drawingPaths = serializableDrawingPaths,
            progressPercentage = state.progressPercentage,
            layers = state.layers,
            calibrationSnapshots = state.calibrationSnapshots
        )
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
        _uiState.update { it.copy(layers = layers) }
        updateRenderer()
    }

    private fun updateRenderer() {
        arRenderer?.updateLayers(_uiState.value.layers)
    }

    // Add new layer logic
    fun addLayer(uri: Uri) {
        val layer = OverlayLayer(
            id = UUID.randomUUID().toString(),
            uri = uri,
            name = "New Layer"
        )
        val layers = _uiState.value.layers + layer
        _uiState.update { it.copy(layers = layers, activeLayerId = layer.id) }
        updateRenderer()
    }
}

sealed class FeedbackEvent {
    data class Toast(val message: String) : FeedbackEvent()
    object VibrateDouble : FeedbackEvent()
    object VibrateSingle : FeedbackEvent()
}