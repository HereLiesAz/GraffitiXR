package com.hereliesaz.graffitixr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.location.Location
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.data.*
import com.hereliesaz.graffitixr.utils.BackgroundRemover
import com.hereliesaz.graffitixr.utils.ImageUtils
import com.hereliesaz.graffitixr.utils.ProjectManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

class MainViewModel(
    private val projectManager: ProjectManager = ProjectManager()
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _feedbackEvent = Channel<FeedbackEvent>(Channel.BUFFERED)
    val feedbackEvent = _feedbackEvent.receiveAsFlow()

    private val _captureEvent = Channel<CaptureEvent>(Channel.BUFFERED)
    val captureEvent = _captureEvent.receiveAsFlow()

    private val _tapFeedback = MutableStateFlow<TapFeedback?>(null)
    val tapFeedback = _tapFeedback.asStateFlow()

    private val _artworkBounds = MutableStateFlow<android.graphics.RectF?>(null)
    val artworkBounds = _artworkBounds.asStateFlow()

    var arRenderer: ArRenderer? = null

    // History Stacks (Global Layer States)
    private val undoStack = ArrayDeque<List<OverlayLayer>>()
    private val redoStack = ArrayDeque<List<OverlayLayer>>()
    private val MAX_HISTORY = 50

    private var layerModsClipboard: OverlayLayer? = null

    // ... (Existing helpers) ...
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

    // ... (Existing adjustment methods) ...
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

    // ... (Existing background/outline methods) ...
    fun onRemoveBackgroundClicked() {
        val activeId = _uiState.value.activeLayerId ?: return
        val context = arRenderer?.context ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            _uiState.value.layers.find { it.id == activeId }?.let { layer ->
                ImageUtils.loadBitmapFromUri(context, layer.uri)?.let { original ->
                    snapshotState()
                    val processed = BackgroundRemover.removeBackground(original)
                    if (processed != null) {
                        val newUri = ImageUtils.saveBitmapToCache(context, processed)
                        updateActiveLayer { it.copy(uri = newUri) }
                    } else {
                        _feedbackEvent.send(FeedbackEvent.Toast("Failed to remove background"))
                    }
                }
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun onLineDrawingClicked() {
        val activeId = _uiState.value.activeLayerId ?: return
        val context = arRenderer?.context ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                _uiState.value.layers.find { it.id == activeId }?.let { layer ->
                    ImageUtils.loadBitmapFromUri(context, layer.uri)?.let { original ->
                        snapshotState()
                        val processed = ImageUtils.generateOutline(original)
                        val newUri = ImageUtils.saveBitmapToCache(context, processed)
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

    // ... (Layer Management) ...
    fun onLayerActivated(id: String) = _uiState.update { it.copy(activeLayerId = id) }
    fun onLayerRenamed(id: String, name: String) {
        _uiState.update { state ->
            state.copy(layers = state.layers.map { if (it.id == id) it.copy(name = name) else it })
        }
    }
    fun onLayerReordered(newOrder: List<String>) {
        snapshotState()
        val map = _uiState.value.layers.associateBy { it.id }
        _uiState.update { it.copy(layers = newOrder.mapNotNull { map[it] }) }
    }
    fun copyLayerModifications(id: String) {
        layerModsClipboard = _uiState.value.layers.find { it.id == id }
        viewModelScope.launch { _feedbackEvent.send(FeedbackEvent.VibrateSingle) }
    }
    fun pasteLayerModifications(id: String) {
        layerModsClipboard?.let { template ->
            updateActiveLayer(saveHistory = true) { target ->
                target.copy(
                    opacity = template.opacity, brightness = template.brightness,
                    contrast = template.contrast, saturation = template.saturation,
                    colorBalanceR = template.colorBalanceR, colorBalanceG = template.colorBalanceG,
                    colorBalanceB = template.colorBalanceB, scale = template.scale,
                    rotationX = template.rotationX, rotationY = template.rotationY,
                    rotationZ = template.rotationZ, blendMode = template.blendMode
                )
            }
            viewModelScope.launch { _feedbackEvent.send(FeedbackEvent.VibrateDouble) }
        }
    }

    // NEW METHODS
    fun onLayerDuplicated(id: String) {
        snapshotState()
        val layer = _uiState.value.layers.find { it.id == id } ?: return
        val newLayer = layer.copy(id = UUID.randomUUID().toString(), name = "${layer.name} (Copy)")
        _uiState.update { it.copy(layers = it.layers + newLayer, activeLayerId = newLayer.id) }
    }

    fun onLayerRemoved(id: String) {
        snapshotState()
        _uiState.update { state ->
            val newLayers = state.layers.filter { it.id != id }
            val newActiveId = if (state.activeLayerId == id) newLayers.firstOrNull()?.id else state.activeLayerId
            state.copy(layers = newLayers, activeLayerId = newActiveId)
        }
    }

    fun onCancelCaptureClicked() {
        _uiState.update { it.copy(isCapturingTarget = false, captureStep = CaptureStep.PREVIEW) }
    }

    fun onUndoClicked() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(_uiState.value.layers.toList())
        _uiState.update { it.copy(layers = undoStack.removeLast()) }
        updateHistoryFlags()
    }

    fun onRedoClicked() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(_uiState.value.layers.toList())
        _uiState.update { it.copy(layers = redoStack.removeLast()) }
        updateHistoryFlags()
    }

    // ... (AR & UI Callbacks) ...
    fun onEditorModeChanged(mode: EditorMode) = _uiState.update { it.copy(editorMode = mode) }
    fun onScaleChanged(s: Float) = updateActiveLayer { it.copy(scale = it.scale * s) }
    fun onArObjectScaleChanged(s: Float) = onScaleChanged(s)
    fun onOffsetChanged(o: Offset) = updateActiveLayer { it.copy(offset = it.offset + o) }
    fun onRotationXChanged(d: Float) = updateActiveLayer { it.copy(rotationX = it.rotationX + d) }
    fun onRotationYChanged(d: Float) = updateActiveLayer { it.copy(rotationY = it.rotationY + d) }
    fun onRotationZChanged(d: Float) = updateActiveLayer { it.copy(rotationZ = it.rotationZ + d) }
    fun onCycleRotationAxis() = _uiState.update { state ->
        val next = when (state.activeRotationAxis) {
            RotationAxis.X -> RotationAxis.Y
            RotationAxis.Y -> RotationAxis.Z
            RotationAxis.Z -> RotationAxis.X
        }
        state.copy(activeRotationAxis = next, showRotationAxisFeedback = true)
    }

    fun onCreateTargetClicked() = _uiState.update { it.copy(isCapturingTarget = true, captureStep = CaptureStep.CHOOSE_METHOD) }
    fun onCaptureShutterClicked() = viewModelScope.launch { _captureEvent.send(CaptureEvent.RequestCapture) }
    fun saveCapturedBitmap(b: Bitmap) {
        _uiState.update { it.copy(capturedTargetImages = listOf(b), captureStep = CaptureStep.REVIEW) }
        arRenderer?.triggerCapture()
    }

    fun setTouchLocked(l: Boolean) = _uiState.update { it.copy(isTouchLocked = l) }
    fun toggleImageLock() = _uiState.update { it.copy(isImageLocked = !it.isImageLocked) }
    fun onToggleFlashlight() {
        _uiState.update { it.copy(isFlashlightOn = !it.isFlashlightOn) }
        arRenderer?.setFlashlight(_uiState.value.isFlashlightOn)
    }
    fun toggleMappingMode() = _uiState.update { it.copy(isMappingMode = !it.isMappingMode) }

    // Project Management
    fun loadAvailableProjects(context: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val projectIds = projectManager.getProjectList(context)
            val projects = projectIds.mapNotNull { id ->
                projectManager.loadProjectMetadata(context, id)
            }
            // Initial sort: Newest first
            val sorted = projects.sortedByDescending { it.lastModified }
            _uiState.update { it.copy(availableProjects = sorted, isLoading = false) }
        }
    }

    fun updateCurrentLocation(location: Location) {
        val gpsData = GpsData(
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = location.altitude,
            accuracy = location.accuracy,
            time = location.time
        )
        _uiState.update { it.copy(gpsData = gpsData) }
        sortProjects(location)
    }

    fun sortProjects(userLocation: Location?) {
        val projects = _uiState.value.availableProjects
        if (projects.isEmpty()) return

        val sorted = if (userLocation != null) {
            // Threshold for "same location" (e.g. 200 meters)
            val threshold = 200f

            // Partition projects into "nearby" and "others"
            val (nearby, others) = projects.partition { project ->
                val dist = getDistance(project.gpsData, userLocation)
                dist != null && dist < threshold
            }

            // Sort both lists by date (newest first)
            val sortedNearby = nearby.sortedByDescending { it.lastModified }
            val sortedOthers = others.sortedByDescending { it.lastModified }

            // Combine: Nearby first, then others
            sortedNearby + sortedOthers
        } else {
            projects.sortedByDescending { it.lastModified }
        }
        _uiState.update { it.copy(availableProjects = sorted) }
    }

    private fun getDistance(gps: GpsData?, location: Location): Float? {
        if (gps == null) return null
        val results = FloatArray(1)
        Location.distanceBetween(gps.latitude, gps.longitude, location.latitude, location.longitude, results)
        return results[0]
    }

    fun openProject(project: ProjectData, context: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val loadedState = projectManager.loadProject(context, project.id)
            if (loadedState != null) {
                _uiState.update {
                    loadedState.copy(
                        showProjectList = false,
                        currentProjectId = project.id,
                        availableProjects = it.availableProjects // Preserve list
                    )
                }
            } else {
                // Handle error
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun autoSaveProject(context: Context, thumbnail: Bitmap? = null) {
        viewModelScope.launch {
            val currentState = _uiState.value
            if (currentState.showProjectList) return@launch // Don't save if in menu

            val projectId = currentState.currentProjectId ?: UUID.randomUUID().toString()
            if (currentState.currentProjectId == null) {
                _uiState.update { it.copy(currentProjectId = projectId) }
            }
            projectManager.saveProject(context, currentState, projectId, thumbnail)
        }
    }

    fun deleteProject(context: Context, projectId: String) {
        projectManager.deleteProject(context, projectId)
        loadAvailableProjects(context)
    }

    fun onNewProject() {
        val newId = UUID.randomUUID().toString()
        _uiState.update { UiState(showProjectList = false, currentProjectId = newId) }
    }

    fun onSaveClicked() {} // Triggered manually if needed
    fun exportProjectToUri(u: Uri) {}
    fun onOnboardingComplete(m: EditorMode) = _uiState.update { it.copy(showOnboardingDialogForMode = null) }
    fun onFeedbackShown() = _uiState.update { it.copy(showRotationAxisFeedback = false) }
    fun onMarkProgressToggled() = _uiState.update { it.copy(isMarkingProgress = !it.isMarkingProgress) }
    fun onDrawingPathFinished(p: List<Offset>) = _uiState.update { it.copy(drawingPaths = it.drawingPaths + listOf(p)) }
    fun updateArtworkBounds(b: android.graphics.RectF) = _uiState.update { it.copy() }
    fun setArPlanesDetected(d: Boolean) = _uiState.update { it.copy(isArPlanesDetected = d) }
    fun onArImagePlaced() = _uiState.update { it.copy(arState = ArState.PLACED) }
    fun onFrameCaptured(b: Bitmap) {}
    fun onProgressUpdate(p: Float, b: Bitmap?) {}
    fun onTrackingFailure(m: String?) {}
    fun updateMappingScore(s: Float) = _uiState.update { it.copy(mappingQualityScore = s) }
    fun finalizeMap() {}
    fun showUnlockInstructions() = _uiState.update { it.copy(showUnlockInstructions = true) }
    fun onOverlayImageSelected(u: Uri) {
        val newLayer = OverlayLayer(uri = u, name = "Layer ${_uiState.value.layers.size + 1}")
        _uiState.update { it.copy(layers = it.layers + newLayer, activeLayerId = newLayer.id) }
    }
    fun onBackgroundImageSelected(u: Uri) = _uiState.update { it.copy(backgroundImageUri = u) }
    fun onImagePickerShown() {}
    fun onDoubleTapHintDismissed() {}
    fun onGestureStart() {}
    fun onGestureEnd() { snapshotState() }
    fun onRefineTargetToggled() {}
    fun onTargetCreationMethodSelected(m: TargetCreationMode) {}
    fun onGridConfigChanged(r: Int, c: Int) {}
    fun onGpsDecision(e: Boolean) {}
    fun onPhotoSequenceFinished() {}
    fun onCalibrationPointCaptured() {}
    fun unwarpImage(l: List<Any>) {}
    fun onRetakeCapture() {}
    fun onRefinementPathAdded(p: RefinementPath) = _uiState.update { it.copy(refinementPaths = it.refinementPaths + p) }
    fun onRefinementModeChanged(b: Boolean) {}
    fun onConfirmTargetCreation() {}
    fun onMagicClicked() {}
    fun checkForUpdates() {}
    fun installLatestUpdate() {}
}
