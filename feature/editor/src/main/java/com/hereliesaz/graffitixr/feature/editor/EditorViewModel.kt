package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import com.hereliesaz.graffitixr.common.model.*
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val slamManager: SlamManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState = _uiState.asStateFlow()

    fun setEditorMode(mode: EditorMode) = _uiState.update { it.copy(editorMode = mode) }

    fun onAddLayer(uri: Uri) {
        val newLayer = Layer(id = UUID.randomUUID().toString(), name = "Layer ${_uiState.value.layers.size + 1}", uri = uri)
        _uiState.update { it.copy(layers = it.layers + newLayer, activeLayerId = newLayer.id) }
    }

    fun onAddBlankLayer() {
        val newLayer = Layer(id = UUID.randomUUID().toString(), name = "New Sketch", isSketch = true)
        _uiState.update { it.copy(layers = it.layers + newLayer, activeLayerId = newLayer.id) }
    }

    fun setBackgroundImage(uri: Uri) { /* Load URI to Bitmap logic */ }
    fun setBackgroundImage(bitmap: Bitmap?) = _uiState.update { it.copy(backgroundBitmap = bitmap) }

    fun toggleHandedness() = _uiState.update { it.copy(isRightHanded = !it.isRightHanded) }
    fun setActiveTool(tool: Tool) = _uiState.update { it.copy(activeTool = tool) }
    fun onLayerActivated(id: String) = _uiState.update { it.copy(activeLayerId = id) }
    fun onLayerRemoved(id: String) = _uiState.update { s -> s.copy(layers = s.layers.filter { it.id != id }) }

    fun onLayerReordered(newIds: List<String>) {
        _uiState.update { state ->
            val idMap = state.layers.associateBy { it.id }
            val reordered = newIds.mapNotNull { idMap[it] }
            state.copy(layers = reordered)
        }
    }

    fun saveProject(name: String) { /* Persistence */ }
    fun exportProject() { /* Export */ }
    fun onRemoveBackgroundClicked() { /* AI Isolate */ }
    fun onLineDrawingClicked() { /* Edge detection */ }
    fun onAdjustClicked() = _uiState.update { it.copy(activePanel = EditorPanel.ADJUST) }
}