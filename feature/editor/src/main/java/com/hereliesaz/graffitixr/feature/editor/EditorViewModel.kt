package com.hereliesaz.graffitixr.feature.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class EditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _exportTrigger = MutableStateFlow(false)
    val exportTrigger: StateFlow<Boolean> = _exportTrigger.asStateFlow()

    fun setEditorMode(mode: EditorMode) {
        _uiState.update { it.copy(editorMode = mode) }
    }

    fun setBackgroundImage(uri: Uri?) { }

    fun setMapPath(path: String) {
        _uiState.update { it.copy(mapPath = path) }
    }

    fun onAddLayer(uri: Uri) { }

    fun onLayerActivated(layerId: String) {
        _uiState.update { it.copy(activeLayerId = layerId) }
    }

    fun onLayerReordered(newOrder: List<String>) {
        _uiState.update { state ->
            val reordered = newOrder.mapNotNull { id -> state.layers.find { it.id == id } }
            state.copy(layers = reordered)
        }
    }

    fun onLayerRenamed(layerId: String, newName: String) {
        _uiState.update { state ->
            val newLayers = state.layers.map {
                if (it.id == layerId) it.copy(name = newName) else it
            }
            state.copy(layers = newLayers)
        }
    }

    fun onLayerRemoved(layerId: String) {
        _uiState.update { state ->
            val newLayers = state.layers.filter { it.id != layerId }
            state.copy(layers = newLayers, activeLayerId = if (state.activeLayerId == layerId) null else state.activeLayerId)
        }
    }

    fun toggleImageLock() {
        _uiState.update { it.copy(isImageLocked = !it.isImageLocked) }
    }

    fun toggleHandedness() {
        _uiState.update { it.copy(isRightHanded = !it.isRightHanded) }
    }

    fun onScaleChanged(scale: Float) {
        updateActiveLayer { it.copy(scale = it.scale * scale) }
    }

    fun onOffsetChanged(offset: Offset) {
        updateActiveLayer { it.copy(offset = it.offset + offset) }
    }

    fun onRotationZChanged(rotation: Float) {
        updateActiveLayer { it.copy(rotationZ = it.rotationZ + rotation) }
    }

    fun onCycleRotationAxis() {
        _uiState.update {
            val nextAxis = when(it.activeRotationAxis) {
                RotationAxis.X -> RotationAxis.Y
                RotationAxis.Y -> RotationAxis.Z
                RotationAxis.Z -> RotationAxis.X
            }
            it.copy(activeRotationAxis = nextAxis)
        }
    }

    fun onGestureStart() {
        _uiState.update { it.copy(gestureInProgress = true) }
    }

    fun setLayerTransform(scale: Float, offset: Offset, rx: Float, ry: Float, rz: Float) {
        updateActiveLayer { it.copy(scale = scale, offset = offset, rotationX = rx, rotationY = ry, rotationZ = rz) }
        _uiState.update { it.copy(gestureInProgress = false) }
    }

    fun onRemoveBackgroundClicked() { }
    fun onLineDrawingClicked() { }

    fun onAdjustClicked() {
        _uiState.update { it.copy(activePanel = EditorPanel.ADJUST) }
    }

    fun onColorClicked() {
        _uiState.update { it.copy(activePanel = EditorPanel.COLOR) }
    }

    fun onCycleBlendMode() {
        updateActiveLayer {
            val modes = BlendMode.values()
            val nextMode = modes[(it.blendMode.ordinal + 1) % modes.size]
            it.copy(blendMode = nextMode)
        }
    }

    fun exportProject() {
        _exportTrigger.value = true
        _uiState.update { it.copy(hideUiForCapture = true) }
    }

    fun onExportComplete() {
        _exportTrigger.value = false
        _uiState.update { it.copy(hideUiForCapture = false) }
    }

    fun saveProject() { }

    private fun updateActiveLayer(transform: (Layer) -> Layer) {
        _uiState.update { state ->
            val activeId = state.activeLayerId ?: return@update state
            val newLayers = state.layers.map {
                if (it.id == activeId) transform(it) else it
            }
            state.copy(layers = newLayers)
        }
    }
}