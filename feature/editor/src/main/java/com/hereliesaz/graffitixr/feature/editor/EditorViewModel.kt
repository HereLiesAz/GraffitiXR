package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.OverlayLayer
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class EditorViewModel(
    private val projectRepository: ProjectRepository
) : ViewModel(), EditorActions {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    // Undo/Redo Stacks
    private val undoStack = ArrayDeque<List<OverlayLayer>>()
    private val redoStack = ArrayDeque<List<OverlayLayer>>()
    
    // Clipboard
    private var layerModsClipboard: OverlayLayer? = null

    init {
        // Sync with Project Repository
        viewModelScope.launch {
            projectRepository.currentProject.collectLatest { project ->
                if (project != null) {
                    _uiState.update { 
                        it.copy(
                            layers = project.layers,
                            drawingPaths = project.drawingPaths.map { path -> path.map { p -> Offset(p.first, p.second) } },
                            progressPercentage = project.progressPercentage
                        ) 
                    }
                    if (_uiState.value.activeLayerId == null && project.layers.isNotEmpty()) {
                        _uiState.update { it.copy(activeLayerId = project.layers.first().id) }
                    }
                }
            }
        }
    }

    // --- Helper to update layer and sync to Repo ---
    private fun updateActiveLayer(saveHistory: Boolean = false, transform: (OverlayLayer) -> OverlayLayer) {
        val activeId = _uiState.value.activeLayerId ?: return
        val currentLayers = _uiState.value.layers
        val activeLayer = currentLayers.find { it.id == activeId } ?: return
        
        if (saveHistory) snapshotState()

        val newLayer = transform(activeLayer)
        val newLayers = currentLayers.map { if (it.id == activeId) newLayer else it }

        _uiState.update { it.copy(layers = newLayers) }
        
        // Sync to Repo
        viewModelScope.launch {
            projectRepository.updateProject { it.copy(layers = newLayers) }
        }
    }
    
    private fun snapshotState() {
        undoStack.addLast(_uiState.value.layers.toList())
        redoStack.clear()
    }

    // --- EditorActions Implementation ---

    override fun onOpacityChanged(v: Float) = updateActiveLayer { it.copy(opacity = v) }
    override fun onBrightnessChanged(v: Float) = updateActiveLayer { it.copy(brightness = v) }
    override fun onContrastChanged(v: Float) = updateActiveLayer { it.copy(contrast = v) }
    override fun onSaturationChanged(v: Float) = updateActiveLayer { it.copy(saturation = v) }
    override fun onColorBalanceRChanged(v: Float) = updateActiveLayer { it.copy(colorBalanceR = v) }
    override fun onColorBalanceGChanged(v: Float) = updateActiveLayer { it.copy(colorBalanceG = v) }
    override fun onColorBalanceBChanged(v: Float) = updateActiveLayer { it.copy(colorBalanceB = v) }

    override fun onUndoClicked() {
        if (undoStack.isNotEmpty()) {
            redoStack.addLast(_uiState.value.layers.toList())
            val prev = undoStack.removeLast()
            _uiState.update { it.copy(layers = prev) }
            viewModelScope.launch { projectRepository.updateProject { it.copy(layers = prev) } }
        }
    }

    override fun onRedoClicked() {
        if (redoStack.isNotEmpty()) {
            undoStack.addLast(_uiState.value.layers.toList())
            val next = redoStack.removeLast()
            _uiState.update { it.copy(layers = next) }
            viewModelScope.launch { projectRepository.updateProject { it.copy(layers = next) } }
        }
    }

    override fun onMagicClicked() {
        // TODO: Alignment logic
    }

    override fun onRemoveBackgroundClicked() {
        // TODO: Background removal
    }

    override fun onLineDrawingClicked() {
        _uiState.update { it.copy(editorMode = EditorMode.DRAW) }
    }

    override fun onCycleBlendMode() {
        updateActiveLayer { 
             // Simple cycle for prototype
             it 
        }
    }

    override fun toggleImageLock() {
        _uiState.update { it.copy(isImageLocked = !it.isImageLocked) }
    }

    override fun onLayerActivated(id: String) {
        _uiState.update { it.copy(activeLayerId = id) }
    }

    override fun onLayerRenamed(id: String, name: String) {
        val newLayers = _uiState.value.layers.map { if (it.id == id) it.copy(name = name) else it }
        _uiState.update { it.copy(layers = newLayers) }
        viewModelScope.launch { projectRepository.updateProject { it.copy(layers = newLayers) } }
    }

    override fun onLayerReordered(newOrder: List<String>) {
        snapshotState()
        val map = _uiState.value.layers.associateBy { it.id }
        val newLayers = newOrder.mapNotNull { map[it] }
        _uiState.update { it.copy(layers = newLayers) }
        viewModelScope.launch { projectRepository.updateProject { it.copy(layers = newLayers) } }
    }

    override fun onLayerDuplicated(id: String) {
        snapshotState()
        val l = _uiState.value.layers.find { it.id == id } ?: return
        val n = l.copy(id = UUID.randomUUID().toString(), name = "${l.name} (Copy)")
        val newLayers = _uiState.value.layers + n
        _uiState.update { it.copy(layers = newLayers, activeLayerId = n.id) }
        viewModelScope.launch { projectRepository.updateProject { it.copy(layers = newLayers) } }
    }

    override fun onLayerRemoved(id: String) {
        snapshotState()
        val newLayers = _uiState.value.layers.filterNot { it.id == id }
        _uiState.update { it.copy(layers = newLayers, activeLayerId = newLayers.firstOrNull()?.id) }
        viewModelScope.launch { projectRepository.updateProject { it.copy(layers = newLayers) } }
    }

    override fun copyLayerModifications(id: String) {
        layerModsClipboard = _uiState.value.layers.find { it.id == id }
    }

    override fun pasteLayerModifications(id: String) {
        layerModsClipboard?.let { t ->
            updateActiveLayer(true) {
                it.copy(
                    opacity = t.opacity, brightness = t.brightness, contrast = t.contrast,
                    saturation = t.saturation, colorBalanceR = t.colorBalanceR,
                    colorBalanceG = t.colorBalanceG, colorBalanceB = t.colorBalanceB,
                    scale = t.scale, rotationX = t.rotationX, rotationY = t.rotationY,
                    rotationZ = t.rotationZ, blendMode = t.blendMode
                )
            }
        }
    }

    override fun onScaleChanged(s: Float) = updateActiveLayer { it.copy(scale = it.scale * s) }
    override fun onOffsetChanged(o: Offset) = updateActiveLayer { it.copy(offset = it.offset + o) } // Assuming OverlayLayer has offset
    override fun onRotationXChanged(d: Float) = updateActiveLayer { it.copy(rotationX = it.rotationX + d) }
    override fun onRotationYChanged(d: Float) = updateActiveLayer { it.copy(rotationY = it.rotationY + d) }
    override fun onRotationZChanged(d: Float) = updateActiveLayer { it.copy(rotationZ = it.rotationZ + d) }

    override fun onCycleRotationAxis() {
        _uiState.update { s ->
            val n = when (s.activeRotationAxis) {
                RotationAxis.X -> RotationAxis.Y
                RotationAxis.Y -> RotationAxis.Z
                RotationAxis.Z -> RotationAxis.X
            }
            s.copy(activeRotationAxis = n, showRotationAxisFeedback = true)
        }
    }

    override fun onGestureStart() {
        _uiState.update { it.copy(gestureInProgress = true) }
    }

    override fun onGestureEnd() {
        _uiState.update { it.copy(gestureInProgress = false) }
    }

    override fun setLayerTransform(scale: Float, offset: Offset, rx: Float, ry: Float, rz: Float) {
        updateActiveLayer(saveHistory = true) {
            it.copy(scale = scale, offset = offset, rotationX = rx, rotationY = ry, rotationZ = rz)
        }
    }

    override fun onFeedbackShown() {
        _uiState.update { it.copy(showRotationAxisFeedback = false) }
    }

    override fun onDoubleTapHintDismissed() {
        _uiState.update { it.copy(showDoubleTapHint = false) }
    }

    override fun onOnboardingComplete(mode: Any) {
        _uiState.update { it.copy(showOnboardingDialogForMode = null) }
    }

    override fun onDrawingPathFinished(path: List<Offset>) {
        val newPaths = _uiState.value.drawingPaths + listOf(path)
        _uiState.update { it.copy(drawingPaths = newPaths) }
        viewModelScope.launch { 
            projectRepository.updateProject { 
                it.copy(drawingPaths = newPaths.map { p -> p.map { o -> Pair(o.x, o.y) } }) 
            } 
        }
    }
}
