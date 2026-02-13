package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.BlendMode
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.EditorPanel
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * ViewModel for the Image Editor feature.
 * Manages the state of the creative workspace, including layer composition,
 * image adjustments, transformation gestures, and editor modes (AR, Mockup, Overlay).
 *
 * This ViewModel interacts with the [ProjectRepository] to persist project state
 * but maintains a separate, optimized in-memory state [EditorUiState] for real-time UI updates.
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val projectRepository: ProjectRepository
) : ViewModel() {

    // Internal mutable state
    private val _uiState = MutableStateFlow(EditorUiState())

    /**
     * Public immutable state flow observed by the Compose UI.
     */
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _exportTrigger = MutableStateFlow(false)
    /**
     * A signal to trigger the export/screenshot process in the UI.
     * When true, the UI should hide controls and capture the canvas.
     */
    val exportTrigger: StateFlow<Boolean> = _exportTrigger.asStateFlow()

    /**
     * Changes the current operational mode of the editor.
     * @param mode The new [EditorMode] (e.g., AR, MOCKUP).
     */
    fun setEditorMode(mode: EditorMode) {
        _uiState.update { it.copy(editorMode = mode) }
    }

    /**
     * Sets the background image for Mockup mode.
     * @param uri The URI of the image to load.
     */
    fun setBackgroundImage(uri: Uri?) {
        // Logic to load bitmap from URI and update state
        // Stub implementation for documentation context
        _uiState.update { it.copy(backgroundImageUri = uri?.toString()) }
    }

    /**
     * Sets the path to the 3D map file for the 3D Mockup mode (Splatting visualization).
     * @param path The file path to the .map (splats) file.
     */
    fun setMapPath(path: String) {
        _uiState.update { it.copy(mapPath = path) }
    }

    /**
     * Adds a new image layer to the composition.
     * @param uri The URI of the image to add.
     */
    fun onAddLayer(uri: Uri) {
        // Logic to create a new Layer object and add to list
    }

    /**
     * Sets the actively selected layer for editing tools.
     * @param layerId The ID of the layer to select.
     */
    fun onLayerActivated(layerId: String) {
        _uiState.update { it.copy(activeLayerId = layerId) }
    }

    /**
     * Updates the Z-order of layers based on a new ID list.
     * @param newOrder A list of layer IDs representing the new stack order (bottom to top).
     */
    fun onLayerReordered(newOrder: List<String>) {
        _uiState.update { state ->
            val reordered = newOrder.mapNotNull { id -> state.layers.find { it.id == id } }
            state.copy(layers = reordered)
        }
    }

    /**
     * Renames a specific layer.
     * @param layerId The ID of the layer.
     * @param newName The new name.
     */
    fun onLayerRenamed(layerId: String, newName: String) {
        _uiState.update { state ->
            val newLayers = state.layers.map {
                if (it.id == layerId) it.copy(name = newName) else it
            }
            state.copy(layers = newLayers)
        }
    }

    /**
     * Removes a layer from the composition.
     * @param layerId The ID of the layer to delete.
     */
    fun onLayerRemoved(layerId: String) {
        _uiState.update { state ->
            val newLayers = state.layers.filter { it.id != layerId }
            state.copy(layers = newLayers, activeLayerId = if (state.activeLayerId == layerId) null else state.activeLayerId)
        }
    }

    /**
     * Toggles the lock state of the active layer (or global interaction lock).
     */
    fun toggleImageLock() {
        _uiState.update { it.copy(isImageLocked = !it.isImageLocked) }
    }

    /**
     * Toggles the UI layout between left-handed and right-handed modes.
     */
    fun toggleHandedness() {
        _uiState.update { it.copy(isRightHanded = !it.isRightHanded) }
    }

    /**
     * Applies a relative scale change to the active layer.
     * @param scale The scale factor delta (e.g., 1.1 for 10% increase).
     */
    fun onScaleChanged(scale: Float) {
        updateActiveLayer { it.copy(scale = it.scale * scale) }
    }

    /**
     * Applies a relative position offset to the active layer.
     * @param offset The 2D offset vector.
     */
    fun onOffsetChanged(offset: Offset) {
        updateActiveLayer { it.copy(offset = it.offset + offset) }
    }

    /**
     * Applies a relative rotation change around the Z-axis (2D rotation).
     * @param rotation The rotation delta in degrees.
     */
    fun onRotationZChanged(rotation: Float) {
        updateActiveLayer { it.copy(rotationZ = it.rotationZ + rotation) }
    }

    /**
     * Cycles through the active rotation axis (X, Y, Z) for 3D transforms.
     * Used when the rotation gesture is mapped to different axes.
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

    /**
     * Signals the start of a transformation gesture.
     */
    fun onGestureStart() {
        _uiState.update { it.copy(gestureInProgress = true) }
    }

    /**
     * Sets absolute transform values for the active layer.
     * Used by precision controls (sliders/knobs).
     */
    fun setLayerTransform(scale: Float, offset: Offset, rx: Float, ry: Float, rz: Float) {
        updateActiveLayer { it.copy(scale = scale, offset = offset, rotationX = rx, rotationY = ry, rotationZ = rz) }
        _uiState.update { it.copy(gestureInProgress = false) }
    }

    /**
     * Removes the background color of the active layer (using simple thresholding or AI).
     */
    fun onRemoveBackgroundClicked() {
        // TODO: Implement background removal logic
    }

    /**
     * Converts the active layer to a line drawing (edge detection).
     */
    fun onLineDrawingClicked() {
        // TODO: Implement edge detection logic
    }

    /**
     * Activates the "Adjust" panel (Brightness, Contrast, etc.).
     */
    fun onAdjustClicked() {
        _uiState.update { it.copy(activePanel = EditorPanel.ADJUST) }
    }

    /**
     * Activates the "Color" panel (Color Balance).
     */
    fun onColorClicked() {
        _uiState.update { it.copy(activePanel = EditorPanel.COLOR) }
    }

    /**
     * Cycles through available blending modes for the active layer.
     */
    fun onCycleBlendMode() {
        updateActiveLayer {
            val modes = BlendMode.entries
            val nextMode = modes[(it.blendMode.ordinal + 1) % modes.size]
            it.copy(blendMode = nextMode)
        }
    }

    /**
     * Initiates the project export flow.
     * Sets a flag to hide UI elements so a clean screenshot can be taken.
     */
    fun exportProject() {
        _exportTrigger.value = true
        _uiState.update { it.copy(hideUiForCapture = true) }
    }

    /**
     * Called when the export/screenshot capture is complete to restore the UI.
     */
    fun onExportComplete() {
        _exportTrigger.value = false
        _uiState.update { it.copy(hideUiForCapture = false) }
    }

    /**
     * Persists the current project state to the repository.
     */
    fun saveProject() {
        // TODO: Map EditorUiState back to GraffitiProject and save
    }

    /**
     * Helper function to modify the currently active layer safely.
     */
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
