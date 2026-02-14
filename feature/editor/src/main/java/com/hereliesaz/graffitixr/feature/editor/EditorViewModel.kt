package com.hereliesaz.graffitixr.feature.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.common.model.BlendMode
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.EditorPanel
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.feature.editor.BackgroundRemover
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel for the Image Editor feature.
 * Manages layer composition, image adjustments, and transformation gestures.
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    @ApplicationContext private val context: Context,
    private val backgroundRemover: BackgroundRemover,
    private val slamManager: SlamManager,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    // Internal mutable state
    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _exportTrigger = MutableStateFlow(false)
    val exportTrigger: StateFlow<Boolean> = _exportTrigger.asStateFlow()

    fun setEditorMode(mode: EditorMode) {
        _uiState.update { it.copy(editorMode = mode) }
    }

    fun setBackgroundImage(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch(dispatchers.io) {
            val bitmap = loadBitmapFromUri(uri)
            _uiState.update {
                it.copy(
                    backgroundImageUri = uri.toString(),
                    backgroundBitmap = bitmap
                )
            }
        }
    }

    fun setMapPath(path: String) {
        _uiState.update { it.copy(mapPath = path) }
    }

    fun onAddLayer(uri: Uri) {
        viewModelScope.launch(dispatchers.io) {
            val bitmap = loadBitmapFromUri(uri)
            if (bitmap != null) {
                val newLayer = Layer(
                    id = UUID.randomUUID().toString(),
                    name = "Layer ${_uiState.value.layers.size + 1}",
                    bitmap = bitmap
                )
                _uiState.update {
                    it.copy(
                        layers = it.layers + newLayer,
                        activeLayerId = newLayer.id
                    )
                }
            }
        }
    }

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

    fun onLayerWarpChanged(layerId: String, newMesh: List<Float>) {
        updateActiveLayer { if (it.id == layerId) it.copy(warpMesh = newMesh) else it }
    }

    // --- Image Processing ---

    fun onRemoveBackgroundClicked() {
        val activeLayer = _uiState.value.layers.find { it.id == _uiState.value.activeLayerId } ?: return

        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch(dispatchers.default) {
            // Correct usage: call instance method on the backgroundRemover instance
            val result = backgroundRemover.removeBackground(activeLayer.bitmap)
            val segmented = result.getOrNull()

            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    layers = state.layers.map {
                        // Ensure 'segmented' is a valid Bitmap before copying
                        if (it.id == activeLayer.id && segmented != null) it.copy(bitmap = segmented) else it
                    }
                )
            }
        }
    }

    fun onLineDrawingClicked() {
        val activeLayer = _uiState.value.layers.find { it.id == _uiState.value.activeLayerId } ?: return

        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch(dispatchers.default) {
            // Use SlamManager for JNI edge detection
            val edged = slamManager.detectEdges(activeLayer.bitmap)

            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    layers = state.layers.map {
                        if (it.id == activeLayer.id && edged != null) it.copy(bitmap = edged) else it
                    }
                )
            }
        }
    }

    // --- Panels ---

    fun onAdjustClicked() {
        _uiState.update { it.copy(activePanel = EditorPanel.ADJUST) }
    }

    fun onColorClicked() {
        _uiState.update { it.copy(activePanel = EditorPanel.COLOR) }
    }

    fun onCycleBlendMode() {
        updateActiveLayer {
            val modes = BlendMode.entries
            val nextMode = modes[(it.blendMode.ordinal + 1) % modes.size]
            it.copy(blendMode = nextMode)
        }
    }

    // --- Export & Save ---

    fun exportProject() {
        _exportTrigger.value = true
        _uiState.update { it.copy(hideUiForCapture = true) }
    }

    fun onExportComplete() {
        _exportTrigger.value = false
        _uiState.update { it.copy(hideUiForCapture = false) }
    }

    fun saveProject() {
        viewModelScope.launch {
            // TODO: Mapping logic to save to Repository
        }
    }

    // --- Utils ---

    private fun updateActiveLayer(transform: (Layer) -> Layer) {
        _uiState.update { state ->
            val activeId = state.activeLayerId ?: return@update state
            val newLayers = state.layers.map {
                if (it.id == activeId) transform(it) else it
            }
            state.copy(layers = newLayers)
        }
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            inputStream?.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}