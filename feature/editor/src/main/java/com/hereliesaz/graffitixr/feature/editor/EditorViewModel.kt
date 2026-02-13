package com.hereliesaz.graffitixr.feature.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.hereliesaz.graffitixr.feature.editor.BackgroundRemover
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.common.model.OverlayLayer

/**
 * ViewModel for the Image Editor feature.
 * Manages layer composition, image adjustments, and transformation gestures.
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val slamManager: SlamManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // Internal mutable state
    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _exportTrigger = MutableStateFlow(false)
    val exportTrigger: StateFlow<Boolean> = _exportTrigger.asStateFlow()

    // Helpers
    private val backgroundRemover = BackgroundRemover(context)

    fun setEditorMode(mode: EditorMode) {
        _uiState.update { it.copy(editorMode = mode) }
    }

    fun setBackgroundImage(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch(Dispatchers.IO) {
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
        viewModelScope.launch(Dispatchers.IO) {
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

    // --- Image Processing ---

    fun onRemoveBackgroundClicked() {
        val activeLayer = _uiState.value.layers.find { it.id == _uiState.value.activeLayerId } ?: return

        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch(Dispatchers.Default) {
            val result = backgroundRemover.removeBackground(activeLayer.bitmap)
            val segmented = result.getOrNull()

            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    layers = state.layers.map {
                        if (it.id == activeLayer.id && segmented != null) it.copy(bitmap = segmented) else it
                    }
                )
            }
        }
    }

    fun onLineDrawingClicked() {
        // Feature temporarily disabled until detectEdges is added to SlamManager JNI
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
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }
            try {
                // 1. Get or Create Project
                val currentProject = projectRepository.currentProject.value ?: GraffitiProject(
                    name = "Project ${System.currentTimeMillis()}"
                )
                val projectId = currentProject.id

                // 2. Save Layers (Serialize Bitmaps to Disk)
                val overlayLayers = _uiState.value.layers.map { layer ->
                    val stream = ByteArrayOutputStream()
                    layer.bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    val bytes = stream.toByteArray()
                    val filename = "layer_${layer.id}.png"

                    // Returns absolute path string
                    val path = projectRepository.saveArtifact(projectId, filename, bytes)
                    val fileUri = Uri.fromFile(File(path))

                    OverlayLayer(
                        id = layer.id,
                        name = layer.name,
                        uri = fileUri,
                        scale = layer.scale,
                        offset = layer.offset,
                        rotationX = layer.rotationX,
                        rotationY = layer.rotationY,
                        rotationZ = layer.rotationZ,
                        isVisible = layer.isVisible,
                        opacity = layer.opacity,
                        blendMode = layer.blendMode,
                        saturation = layer.saturation,
                        contrast = layer.contrast,
                        brightness = layer.brightness,
                        colorBalanceR = layer.colorBalanceR,
                        colorBalanceG = layer.colorBalanceG,
                        colorBalanceB = layer.colorBalanceB
                    )
                }

                // 3. Save World (SLAM Map) if data exists
                var mapPath = _uiState.value.mapPath
                val mapFilename = "world_${System.currentTimeMillis()}.bin"
                val projectDir = File(context.filesDir, "projects/$projectId")
                if (!projectDir.exists()) projectDir.mkdirs()

                val mapFile = File(projectDir, mapFilename)
                if (slamManager.saveMap(mapFile.absolutePath)) {
                    mapPath = mapFile.absolutePath
                }

                // 4. Update Project Object
                val updatedProject = currentProject.copy(
                    lastModified = System.currentTimeMillis(),
                    layers = overlayLayers,
                    mapPath = mapPath,
                    isRightHanded = _uiState.value.isRightHanded,
                    // Persist background if set
                    backgroundImageUri = _uiState.value.backgroundImageUri?.let { Uri.parse(it) }
                )

                // 5. Persist to Disk
                projectRepository.updateProject(updatedProject)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
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
