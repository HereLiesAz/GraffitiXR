package com.hereliesaz.graffitixr.feature.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.common.model.*
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    @ApplicationContext private val context: Context,
    private val backgroundRemover: BackgroundRemover,
    private val slamManager: SlamManager,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch(dispatchers.main) {
            projectRepository.currentProject.collect { project ->
                if (project != null) {
                    _uiState.update { it.copy(projectId = project.id) }
                }
            }
        }
    }

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

    fun onLayerRemoved(id: String) {
        _uiState.update { state ->
            val updatedLayers = state.layers.filter { it.id != id }
            val newActiveId = if (state.activeLayerId == id) updatedLayers.firstOrNull()?.id else state.activeLayerId
            state.copy(layers = updatedLayers, activeLayerId = newActiveId)
        }
    }

    fun onLayerReordered(newIds: List<String>) {
        _uiState.update { state ->
            val idMap = state.layers.associateBy { it.id }
            val reordered = newIds.mapNotNull { idMap[it] }
            state.copy(layers = reordered)
        }
    }

    fun saveProject(name: String = "New Project") {
        viewModelScope.launch(dispatchers.default) {
            val project = projectRepository.currentProject.value
            if (project == null) {
                projectRepository.createProject(GraffitiProject(name = name))
            } else {
                projectRepository.updateProject(project.copy(name = name))
            }
        }
    }

    fun exportProject() { /* Export Logic stub */ }

    fun onRemoveBackgroundClicked() {
        val state = _uiState.value
        val layerId = state.activeLayerId ?: return
        val layer = state.layers.find { it.id == layerId } ?: return
        val projectId = state.projectId ?: return

        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch(dispatchers.default) {
            val bitmap = BitmapUtils.getBitmapFromUri(context, layer.uri!!)
            if (bitmap != null) {
                val result = backgroundRemover.removeBackground(bitmap)
                result.onSuccess { fgBitmap ->
                    val path = projectRepository.saveArtifact(projectId, "bg_removed_${System.currentTimeMillis()}.png", BitmapUtils.bitmapToByteArray(fgBitmap))
                    updateLayerUri(layerId, Uri.parse("file://$path"))
                }
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun onLineDrawingClicked() {
        val state = _uiState.value
        val layerId = state.activeLayerId ?: return
        val layer = state.layers.find { it.id == layerId } ?: return
        val projectId = state.projectId ?: return

        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch(dispatchers.default) {
            val bitmap = BitmapUtils.getBitmapFromUri(context, layer.uri!!)
            if (bitmap != null) {
                val resultBitmap = com.hereliesaz.graffitixr.common.util.ImageProcessor.detectEdges(bitmap)
                if (resultBitmap != null) {
                    val path = projectRepository.saveArtifact(projectId, "line_art_${System.currentTimeMillis()}.png", BitmapUtils.bitmapToByteArray(resultBitmap))
                    updateLayerUri(layerId, Uri.parse("file://$path"))
                }
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private fun updateLayerUri(id: String, uri: Uri) {
        _uiState.update { state ->
            val updatedLayers = state.layers.map { if (it.id == id) it.copy(uri = uri) else it }
            state.copy(layers = updatedLayers)
        }
    }

    fun onAdjustClicked() {
        _uiState.update {
            it.copy(activePanel = if (it.activePanel == EditorPanel.ADJUST) EditorPanel.NONE else EditorPanel.ADJUST)
        }
    }

    fun onDismissPanel() {
        _uiState.update { it.copy(activePanel = EditorPanel.NONE) }
    }

    fun onTransformGesture(pan: androidx.compose.ui.geometry.Offset, zoom: Float, rotation: Float) {
        updateActiveLayer { layer ->
            layer.copy(
                scale = layer.scale * zoom,
                offset = layer.offset + pan,
                rotationZ = layer.rotationZ + rotation
            )
        }
    }

    fun toggleImageLock() {
        updateActiveLayer { it.copy(isImageLocked = !it.isImageLocked) }
    }

    // --- Action Methods matching EditorUi Signatures ---
    fun onOpacityChanged(value: Float) = updateActiveLayer { it.copy(opacity = value) }
    fun onBrightnessChanged(value: Float) = updateActiveLayer { it.copy(brightness = value) }
    fun onContrastChanged(value: Float) = updateActiveLayer { it.copy(contrast = value) }
    fun onSaturationChanged(value: Float) = updateActiveLayer { it.copy(saturation = value) }
    fun onColorBalanceRChanged(value: Float) = updateActiveLayer { it.copy(colorBalanceR = value) }
    fun onColorBalanceGChanged(value: Float) = updateActiveLayer { it.copy(colorBalanceG = value) }
    fun onColorBalanceBChanged(value: Float) = updateActiveLayer { it.copy(colorBalanceB = value) }

    fun onUndoClicked() { /* Decrease undo index stack */ }
    fun onRedoClicked() { /* Increase undo index stack */ }
    fun onMagicClicked() { /* Trigger auto-alignment logic */ }

    fun onAdjustmentStart() = _uiState.update { it.copy(gestureInProgress = true) }
    fun onAdjustmentEnd() = _uiState.update { it.copy(gestureInProgress = false) }

    private fun updateActiveLayer(transform: (Layer) -> Layer) {
        _uiState.update { state ->
            val id = state.activeLayerId ?: return@update state
            state.copy(layers = state.layers.map { if (it.id == id) transform(it) else it })
        }
    }
}