package com.hereliesaz.graffitixr

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.data.OverlayLayer
import com.hereliesaz.graffitixr.data.RotationAxis
import com.hereliesaz.graffitixr.utils.BackgroundRemover
import com.hereliesaz.graffitixr.utils.ImageUtils
import com.hereliesaz.graffitixr.utils.ProjectManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class MainViewModel(
    private val projectManager: ProjectManager = ProjectManager()
) : ViewModel() {

    // --- State & Events ---
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _feedbackEvent = Channel<FeedbackEvent>(Channel.BUFFERED)
    val feedbackEvent = _feedbackEvent.receiveAsFlow()

    private val _captureEvent = Channel<CaptureEvent>(Channel.BUFFERED)
    val captureEvent = _captureEvent.receiveAsFlow()

    private val _tapFeedback = MutableStateFlow<Any?>(null)
    val tapFeedback = _tapFeedback.asStateFlow()

    private val _artworkBounds = MutableStateFlow<android.graphics.RectF?>(null)
    val artworkBounds = _artworkBounds.asStateFlow()

    var arRenderer: ArRenderer? = null

    // --- History (Global Scope) ---
    private val undoStack = ArrayDeque<List<OverlayLayer>>()
    private val redoStack = ArrayDeque<List<OverlayLayer>>()
    private val MAX_HISTORY = 50

    // --- Clipboard ---
    private var layerModsClipboard: OverlayLayer? = null

    // ============================================================================================
    // UNDO / REDO (GLOBAL)
    // ============================================================================================

    private fun snapshotState() {
        if (undoStack.size >= MAX_HISTORY) undoStack.removeFirst()
        // Save copy of the list
        undoStack.addLast(_uiState.value.layers.toList())
        redoStack.clear()
        updateUndoRedoAvailability()
    }

    private fun updateUndoRedoAvailability() {
        _uiState.update {
            it.copy(canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty())
        }
    }

    fun onUndoClicked() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(_uiState.value.layers.toList())
        val prev = undoStack.removeLast()
        _uiState.update { it.copy(layers = prev) }
        updateUndoRedoAvailability()
    }

    fun onRedoClicked() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(_uiState.value.layers.toList())
        val next = redoStack.removeLast()
        _uiState.update { it.copy(layers = next) }
        updateUndoRedoAvailability()
    }

    // ============================================================================================
    // LAYER MODIFICATION (ACTIVE LAYER ONLY)
    // ============================================================================================

    private fun updateActiveLayer(
        saveHistory: Boolean = false,
        block: (OverlayLayer) -> OverlayLayer
    ) {
        val activeId = _uiState.value.activeLayerId ?: return
        if (saveHistory) snapshotState()

        _uiState.update { state ->
            val updatedLayers = state.layers.map { layer ->
                if (layer.id == activeId) block(layer) else layer
            }
            state.copy(layers = updatedLayers)
        }
    }

    // --- Knobs ---
    fun onOpacityChanged(v: Float) = updateActiveLayer { it.copy(opacity = v) }
    fun onBrightnessChanged(v: Float) = updateActiveLayer { it.copy(brightness = v) }
    fun onContrastChanged(v: Float) = updateActiveLayer { it.copy(contrast = v) }
    fun onSaturationChanged(v: Float) = updateActiveLayer { it.copy(saturation = v) }

    // --- Color Balance ---
    fun onColorBalanceRChanged(v: Float) = updateActiveLayer { it.copy(colorBalanceR = v) }
    fun onColorBalanceGChanged(v: Float) = updateActiveLayer { it.copy(colorBalanceG = v) }
    fun onColorBalanceBChanged(v: Float) = updateActiveLayer { it.copy(colorBalanceB = v) }

    // --- Blending ---
    fun onCycleBlendMode() = updateActiveLayer(saveHistory = true) { layer ->
        val nextMode = when (layer.blendMode) {
            BlendMode.SrcOver -> BlendMode.Screen
            BlendMode.Screen -> BlendMode.Multiply
            BlendMode.Multiply -> BlendMode.Overlay
            BlendMode.Overlay -> BlendMode.Difference
            else -> BlendMode.SrcOver
        }
        layer.copy(blendMode = nextMode)
    }

    // ============================================================================================
    // PROCESSING TOOLS (Isolate / Outline)
    // ============================================================================================

    fun onRemoveBackgroundClicked() {
        val activeId = _uiState.value.activeLayerId ?: return
        val context = arRenderer?.context ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val layer = _uiState.value.layers.find { it.id == activeId }

            if (layer != null) {
                // Load Bitmap
                val original = ImageUtils.loadBitmapFromUri(context, layer.uri)
                if (original != null) {
                    snapshotState() // Destructive action
                    // Use existing BackgroundRemover
                    val processed = BackgroundRemover.removeBackground(original)
                    val newUri = ImageUtils.saveBitmapToCache(context, processed)
                    updateActiveLayer { it.copy(uri = newUri) }
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
            val layer = _uiState.value.layers.find { it.id == activeId }

            if (layer != null) {
                val original = ImageUtils.loadBitmapFromUri(context, layer.uri)
                if (original != null) {
                    snapshotState()
                    // Assuming ImageUtils contains the OpenCV edge detection logic we discussed
                    val processed = ImageUtils.generateOutline(original)
                    val newUri = ImageUtils.saveBitmapToCache(context, processed)
                    updateActiveLayer { it.copy(uri = newUri) }
                }
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun onMagicClicked() {
        // Auto-enhance or alignment logic
        viewModelScope.launch {
            _feedbackEvent.send(FeedbackEvent.VibrateSingle)
            // Implementation pending specific requirement
        }
    }

    // ============================================================================================
    // LAYER MANAGEMENT (AzNavRail Callbacks)
    // ============================================================================================

    fun onOverlayImageSelected(uri: Uri) {
        snapshotState()
        val newLayer = OverlayLayer(
            id = UUID.randomUUID().toString(),
            uri = uri,
            name = "Layer ${_uiState.value.layers.size + 1}"
        )
        _uiState.update {
            it.copy(
                layers = it.layers + newLayer,
                activeLayerId = newLayer.id,
                showImagePicker = false
            )
        }
    }

    fun onLayerActivated(layerId: String) {
        _uiState.update { it.copy(activeLayerId = layerId) }
    }

    // Called by AzNavRail's azRailRelocItem via onRelocate
    fun onLayerReordered(newOrderIds: List<String>) {
        snapshotState()
        val currentLayersMap = _uiState.value.layers.associateBy { it.id }
        // Reconstruct list based on new ID order
        val reorderedLayers = newOrderIds.mapNotNull { currentLayersMap[it] }

        if (reorderedLayers.size == _uiState.value.layers.size) {
            _uiState.update { it.copy(layers = reorderedLayers) }
        }
    }

    fun onLayerRenamed(layerId: String, newName: String) {
        _uiState.update { state ->
            val updated = state.layers.map { if (it.id == layerId) it.copy(name = newName) else it }
            state.copy(layers = updated)
        }
    }

    fun onLayerDuplicated(layerId: String) {
        snapshotState()
        val layer = _uiState.value.layers.find { it.id == layerId } ?: return
        val newLayer = layer.copy(id = UUID.randomUUID().toString(), name = "${layer.name} Copy")
        _uiState.update { it.copy(layers = it.layers + newLayer, activeLayerId = newLayer.id) }
    }

    fun onLayerRemoved(layerId: String) {
        snapshotState()
        _uiState.update { state ->
            state.copy(
                layers = state.layers.filter { it.id != layerId },
                activeLayerId = if (state.activeLayerId == layerId) null else state.activeLayerId
            )
        }
    }

    fun copyLayerModifications(layerId: String) {
        val layer = _uiState.value.layers.find { it.id == layerId } ?: return
        layerModsClipboard = layer
        viewModelScope.launch { _feedbackEvent.send(FeedbackEvent.VibrateSingle) }
    }

    fun pasteLayerModifications(layerId: String) {
        val template = layerModsClipboard ?: return
        updateActiveLayer(saveHistory = true) { target ->
            target.copy(
                opacity = template.opacity,
                brightness = template.brightness,
                contrast = template.contrast,
                saturation = template.saturation,
                colorBalanceR = template.colorBalanceR,
                colorBalanceG = template.colorBalanceG,
                colorBalanceB = template.colorBalanceB,
                scale = template.scale,
                rotationX = template.rotationX,
                rotationY = template.rotationY,
                rotationZ = template.rotationZ,
                blendMode = template.blendMode
            )
        }
        viewModelScope.launch { _feedbackEvent.send(FeedbackEvent.VibrateDouble) }
    }

    fun toggleImageLock() {
        _uiState.update { it.copy(isImageLocked = !it.isImageLocked) }
    }

    // ============================================================================================
    // TRANSFORMS & GESTURES
    // ============================================================================================

    fun onScaleChanged(scale: Float) = updateActiveLayer { it.copy(scale = it.scale * scale) }
    fun onArObjectScaleChanged(zoom: Float) = onScaleChanged(zoom)

    fun onOffsetChanged(delta: Offset) = updateActiveLayer { it.copy(offset = it.offset + delta) }

    fun onRotationXChanged(delta: Float) = updateActiveLayer { it.copy(rotationX = it.rotationX + delta) }
    fun onRotationYChanged(delta: Float) = updateActiveLayer { it.copy(rotationY = it.rotationY + delta) }
    fun onRotationZChanged(delta: Float) = updateActiveLayer { it.copy(rotationZ = it.rotationZ + delta) }

    fun onCycleRotationAxis() {
        _uiState.update {
            val next = when (it.activeRotationAxis) {
                RotationAxis.X -> RotationAxis.Y
                RotationAxis.Y -> RotationAxis.Z
                RotationAxis.Z -> RotationAxis.X
            }
            it.copy(activeRotationAxis = next, showRotationAxisFeedback = true)
        }
    }

    fun onFeedbackShown() { _uiState.update { it.copy(showRotationAxisFeedback = false) } }
    fun onGestureStart() { /* Pause UI updates if needed */ }
    fun onGestureEnd() { snapshotState() } // Commit transform on gesture end

    // ============================================================================================
    // BOILERPLATE (Required by MainScreen.kt)
    // ============================================================================================

    fun onEditorModeChanged(mode: EditorMode) { _uiState.update { it.copy(editorMode = mode) } }
    fun onImagePickerShown() { _uiState.update { it.copy(showImagePicker = true) } }
    fun onBackgroundImageSelected(uri: Uri) { _uiState.update { it.copy(backgroundImageUri = uri) } }
    fun onCreateTargetClicked() {
        _uiState.update { it.copy(isCapturingTarget = true, captureStep = CaptureStep.PREVIEW) }
        arRenderer?.isAnchorReplacementAllowed = true
    }
    fun onCaptureShutterClicked() { viewModelScope.launch { _captureEvent.send(CaptureEvent.RequestCapture) } }
    fun saveCapturedBitmap(bitmap: Bitmap) {
        _uiState.update { it.copy(capturedTargetImages = listOf(bitmap), captureStep = CaptureStep.REVIEW) }
    }
    fun onCancelCaptureClicked() { _uiState.update { it.copy(isCapturingTarget = false) } }
    fun onConfirmTargetCreation() {
        _uiState.update { it.copy(isCapturingTarget = false, isArTargetCreated = true) }
        arRenderer?.isAnchorReplacementAllowed = false
    }
    fun onRetakeCapture() {
        _uiState.update { it.copy(capturedTargetImages = emptyList(), captureStep = CaptureStep.PREVIEW) }
    }
    fun setTouchLocked(locked: Boolean) { _uiState.update { it.copy(isTouchLocked = locked) } }
    fun showUnlockInstructions() { _uiState.update { it.copy(showUnlockInstructions = true) } }
    fun onDoubleTapHintDismissed() { _uiState.update { it.copy(showDoubleTapHint = false) } }
    fun onToggleFlashlight() { arRenderer?.setFlashlight(true) }
    fun onMarkProgressToggled() { _uiState.update { it.copy(isMarkingProgress = !it.isMarkingProgress) } }
    fun onDrawingPathFinished(path: android.graphics.Path) {
        _uiState.update { it.copy(drawingPaths = it.drawingPaths + path) }
    }

    // Project Management Stubs
    fun getProjectList(): List<String> = emptyList()
    fun loadProject(name: String) {}
    fun deleteProject(name: String) {}
    fun onNewProject() { _uiState.update { UiState() } }
    fun onSaveClicked() {}
    fun exportProjectToUri(uri: Uri) {}

    // AR / Mapping Stubs
    fun setArPlanesDetected(b: Boolean) { _uiState.update { it.copy(isArPlanesDetected = b) } }
    fun onArImagePlaced() { _uiState.update { it.copy(arState = com.hereliesaz.graffitixr.data.ArState.PLACED) } }
    fun updateMappingScore(s: Float) { _uiState.update { it.copy(mappingQualityScore = s) } }
    fun toggleMappingMode() { _uiState.update { it.copy(isMappingMode = !it.isMappingMode) } }
    fun finalizeMap() {}
    fun onRefineTargetToggled() {}
    fun onRefinementPathAdded(p: android.graphics.Path) {}
    fun onRefinementModeChanged(b: Boolean) {}
    fun onTargetCreationMethodSelected(m: TargetCreationMode) {}
    fun onGridConfigChanged(r: Int, c: Int) {}
    fun onGpsDecision(e: Boolean) {}
    fun onPhotoSequenceFinished() {}
    fun onCalibrationPointCaptured() {}
    fun unwarpImage(l: List<Any>) {}
    fun checkForUpdates() {}
    fun installLatestUpdate() {}
    fun onOnboardingComplete(mode: EditorMode) {}
}