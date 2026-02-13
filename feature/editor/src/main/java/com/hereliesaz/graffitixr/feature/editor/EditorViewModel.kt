package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.core.domain.repository.ProjectRepository // IMPORT FROM DOMAIN
import com.hereliesaz.graffitixr.nativebridge.GraffitiJNI
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Image Editor feature.
 * Handles the manipulation of layers, background images, and various editing tools
 * (adjustments, transforms, blending).
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val projectRepository: ProjectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _exportTrigger = MutableStateFlow(false)
    /**
     * A flag to trigger the export/save process in the UI.
     */
    val exportTrigger: StateFlow<Boolean> = _exportTrigger.asStateFlow()

    /**
     * Changes the current operating mode of the editor.
     * @param mode The new [EditorMode].
     */
    fun setEditorMode(mode: EditorMode) {
        _uiState.update { it.copy(editorMode = mode) }
    }

    fun setBackgroundImage(uri: Uri?) { }

    /**
     * Sets the path to the 3D map file for the 3D Mockup mode.
     * @param path The file path to the .map (splats) file.
     */
    fun setMapPath(path: String) {
        _uiState.update { it.copy(mapPath = path) }
    }

    fun onAddLayer(uri: Uri) { }

    /**
     * Sets the actively selected layer for editing.
     * @param layerId The ID of the layer to select.
     */
    fun onLayerActivated(layerId: String) {
        _uiState.update { it.copy(activeLayerId = layerId) }
    }

    /**
     * Updates the z-order of layers.
     * @param newOrder A list of layer IDs in the new order.
     */
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

            val warpedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            val bytes = GraffitiJNI.extractFeaturesFromBitmap(warpedBitmap)
            val meta = GraffitiJNI.extractFeaturesMeta(warpedBitmap)

            if (bytes != null && meta != null) {
                val filename = "target_${System.currentTimeMillis()}.orb"
                projectRepository.saveArtifact(project.id, filename, bytes)
                projectRepository.updateTargetFingerprint(project.id, filename)
                GraffitiJNI.setTargetDescriptors(bytes, meta[0], meta[1], meta[2])
            }

    fun onRotationZChanged(rotation: Float) {
        updateActiveLayer { it.copy(rotationZ = it.rotationZ + rotation) }
    }

    /**
     * Cycles through the active rotation axis (X, Y, Z) for 3D transforms.
     */
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

    /**
     * Initiates the project export flow. Hides UI overlays.
     */
    fun exportProject() {
        _exportTrigger.value = true
        _uiState.update { it.copy(hideUiForCapture = true) }
    }

    /**
     * Called when export is complete to restore the UI.
     */
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
            if (warpedBitmap != bitmap) warpedBitmap.recycle()
            _isProcessing.value = false
        }
    }
}
