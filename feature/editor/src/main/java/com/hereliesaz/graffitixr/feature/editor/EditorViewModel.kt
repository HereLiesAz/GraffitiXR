package com.hereliesaz.graffitixr.feature.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.common.model.*
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.data.ProjectManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * Manages the state and logic for the Image Editor / Projector.
 * Handles Layer composition, Undo/Redo stacks, and Project I/O.
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val projectManager: ProjectManager,
    @ApplicationContext private val context: Context,
    private val backgroundRemover: BackgroundRemover,
    private val slamManager: SlamManager,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState = _uiState.asStateFlow()

    // Undo/Redo Stacks storing snapshots of the layer list
    private val undoStack = ArrayDeque<List<Layer>>()
    private val redoStack = ArrayDeque<List<Layer>>()
    private val MAX_STACK_SIZE = 20

    init {
        viewModelScope.launch(dispatchers.main) {
            projectRepository.currentProject.collect { project ->
                if (project != null) {
                    _uiState.update {
                        it.copy(
                            projectId = project.id,
                            layers = project.layers.map { overlayLayer -> overlayLayer.toLayer() }
                        )
                    }
                    // Load background if exists
                    val bgUri = project.backgroundImageUri
                    if (bgUri != null) {
                        setBackgroundImage(bgUri)
                    }
                }
            }
        }
    }

    fun setEditorMode(mode: EditorMode) = _uiState.update { it.copy(editorMode = mode) }

    // --- State Management with History ---

    private fun pushHistory() {
        val currentLayers = _uiState.value.layers
        if (undoStack.isNotEmpty() && undoStack.last() == currentLayers) return

        undoStack.addLast(currentLayers)
        if (undoStack.size > MAX_STACK_SIZE) undoStack.removeFirst()
        redoStack.clear()
        updateHistoryCounts()
    }

    private fun updateHistoryCounts() {
        _uiState.update {
            it.copy(
                undoCount = undoStack.size,
                redoCount = redoStack.size
            )
        }
    }

    fun onUndoClicked() {
        if (undoStack.isEmpty()) return
        val currentState = _uiState.value.layers
        redoStack.addLast(currentState)

        val previousState = undoStack.removeLast()
        _uiState.update { it.copy(layers = previousState) }
        updateHistoryCounts()
        saveProject() // Auto-save on undo
    }

    fun onRedoClicked() {
        if (redoStack.isEmpty()) return
        val currentState = _uiState.value.layers
        undoStack.addLast(currentState)

        val nextState = redoStack.removeLast()
        _uiState.update { it.copy(layers = nextState) }
        updateHistoryCounts()
        saveProject()
    }

    // --- Layer Operations ---

    fun onAddLayer(uri: Uri) {
        pushHistory()
        viewModelScope.launch(dispatchers.io) {
            // Validate URI and dimensions before adding
            val dim = BitmapUtils.getBitmapDimensions(context, uri)
            if (dim.first > 0 && dim.second > 0) {
                val newLayer = Layer(
                    id = UUID.randomUUID().toString(),
                    name = "Layer ${_uiState.value.layers.size + 1}",
                    uri = uri,
                    isVisible = true
                )
                withContext(dispatchers.main) {
                    _uiState.update {
                        it.copy(layers = it.layers + newLayer, activeLayerId = newLayer.id)
                    }
                    saveProject()
                }
            } else {
                withContext(dispatchers.main) {
                    Toast.makeText(context, "Invalid image format", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun onAddBlankLayer() {
        pushHistory()
        val newLayer = Layer(id = UUID.randomUUID().toString(), name = "Sketch", isSketch = true)
        _uiState.update { it.copy(layers = it.layers + newLayer, activeLayerId = newLayer.id) }
    }

    fun setBackgroundImage(uri: Uri) {
        viewModelScope.launch(dispatchers.io) {
            _uiState.update { it.copy(isLoading = true) }
            val bitmap = BitmapUtils.getBitmapFromUri(context, uri)
            _uiState.update { it.copy(backgroundBitmap = bitmap, isLoading = false) }
        }
    }

    // --- Transforms & Tools ---

    fun toggleHandedness() = _uiState.update { it.copy(isRightHanded = !it.isRightHanded) }
    fun setActiveTool(tool: Tool) = _uiState.update { it.copy(activeTool = tool) }

    fun onLayerActivated(id: String) = _uiState.update { it.copy(activeLayerId = id) }

    fun onLayerRemoved(id: String) {
        pushHistory()
        _uiState.update { state ->
            val updatedLayers = state.layers.filter { it.id != id }
            val newActiveId = if (state.activeLayerId == id) updatedLayers.firstOrNull()?.id else state.activeLayerId
            state.copy(layers = updatedLayers, activeLayerId = newActiveId)
        }
        saveProject()
    }

    fun onLayerReordered(newIds: List<String>) {
        pushHistory()
        _uiState.update { state ->
            val idMap = state.layers.associateBy { it.id }
            val reordered = newIds.mapNotNull { idMap[it] }
            state.copy(layers = reordered)
        }
    }

    // --- Persistence ---

    fun saveProject(name: String? = null) {
        viewModelScope.launch(dispatchers.io) {
            val currentProject = projectRepository.currentProject.value
            val updatedLayers = _uiState.value.layers.map { it.toOverlayLayer() }

            val projectToSave = if (currentProject == null) {
                GraffitiProject(
                    name = name ?: "New Project",
                    layers = updatedLayers
                )
            } else {
                currentProject.copy(
                    name = name ?: currentProject.name,
                    layers = updatedLayers,
                    lastModified = System.currentTimeMillis()
                )
            }

            if (currentProject == null) {
                projectRepository.createProject(projectToSave)
            } else {
                projectRepository.updateProject(projectToSave)
            }
        }
    }

    fun exportProject() {
        val project = projectRepository.currentProject.value ?: return
        val filename = "${project.name.replace(" ", "_")}_export.gxr"

        viewModelScope.launch(dispatchers.io) {
            _uiState.update { it.copy(isLoading = true) }
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        projectManager.exportProjectToUri(context, project.id, uri)
                        withContext(dispatchers.main) {
                            _uiState.update { it.copy(isLoading = false) }
                            Toast.makeText(context, "Project exported to Downloads", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        throw java.io.IOException("Failed to create MediaStore entry")
                    }
                } else {
                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    val file = File(downloadsDir, filename)
                    val uri = Uri.fromFile(file)
                    projectManager.exportProjectToUri(context, project.id, uri)
                    withContext(dispatchers.main) {
                        _uiState.update { it.copy(isLoading = false) }
                        Toast.makeText(context, "Project exported to ${file.absolutePath}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(dispatchers.main) {
                    _uiState.update { it.copy(isLoading = false) }
                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // --- Image Processing ---

    fun onRemoveBackgroundClicked() {
        val state = _uiState.value
        val layerId = state.activeLayerId ?: return
        val layer = state.layers.find { it.id == layerId } ?: return
        val projectId = state.projectId ?: return

        val uri = layer.uri ?: return

        pushHistory()
        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch(dispatchers.default) {
            val bitmap = BitmapUtils.getBitmapFromUri(context, uri)
            if (bitmap != null) {
                val result = backgroundRemover.removeBackground(bitmap)
                result.onSuccess { fgBitmap ->
                    val path = projectRepository.saveArtifact(projectId, "bg_removed_${System.currentTimeMillis()}.png", BitmapUtils.bitmapToByteArray(fgBitmap))
                    updateLayerUri(layerId, Uri.parse("file://$path"))
                }
                result.onFailure {
                    // Handle error
                }
            }
            withContext(dispatchers.main) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun onLineDrawingClicked() {
        val state = _uiState.value
        val layerId = state.activeLayerId ?: return
        val layer = state.layers.find { it.id == layerId } ?: return
        val projectId = state.projectId ?: return

        val uri = layer.uri ?: return

        pushHistory()
        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch(dispatchers.default) {
            val bitmap = BitmapUtils.getBitmapFromUri(context, uri)
            if (bitmap != null) {
                val resultBitmap = com.hereliesaz.graffitixr.common.util.ImageProcessor.detectEdges(bitmap)
                if (resultBitmap != null) {
                    val path = projectRepository.saveArtifact(projectId, "line_art_${System.currentTimeMillis()}.png", BitmapUtils.bitmapToByteArray(resultBitmap))
                    updateLayerUri(layerId, Uri.parse("file://$path"))
                }
            }
            withContext(dispatchers.main) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun onMagicClicked() {
        pushHistory()
        // Auto-adjust brightness/contrast
        updateActiveLayer {
            it.copy(brightness = 0.1f, contrast = 1.2f, saturation = 1.1f)
        }
    }

    private fun updateLayerUri(id: String, uri: Uri) {
        _uiState.update { state ->
            val updatedLayers = state.layers.map { if (it.id == id) it.copy(uri = uri) else it }
            state.copy(layers = updatedLayers)
        }
        saveProject()
    }

    // --- UI Interactions ---

    fun onAdjustClicked() {
        _uiState.update {
            it.copy(activePanel = if (it.activePanel == EditorPanel.ADJUST) EditorPanel.NONE else EditorPanel.ADJUST)
        }
    }

    fun onBalanceClicked() {
        _uiState.update {
            it.copy(activePanel = if (it.activePanel == EditorPanel.COLOR) EditorPanel.NONE else EditorPanel.COLOR)
        }
    }

    fun onDismissPanel() {
        _uiState.update { it.copy(activePanel = EditorPanel.NONE) }
    }

    fun onTransformGesture(pan: androidx.compose.ui.geometry.Offset, zoom: Float, rotation: Float) {
        // Real-time gestures do not push history to avoid flooding the stack
        updateActiveLayer { layer ->
            layer.copy(
                scale = layer.scale * zoom,
                offset = layer.offset + pan,
                rotationZ = layer.rotationZ + rotation
            )
        }
    }

    // Call this on gesture end to save the final state
    fun onGestureEnd() {
        saveProject()
        _uiState.update { it.copy(gestureInProgress = false) }
    }

    fun onGestureStart() {
        pushHistory()
        _uiState.update { it.copy(gestureInProgress = true) }
    }

    fun toggleImageLock() {
        pushHistory()
        updateActiveLayer { it.copy(isImageLocked = !it.isImageLocked) }
    }

    fun onOpacityChanged(value: Float) = updateActiveLayer { it.copy(opacity = value) }
    fun onBrightnessChanged(value: Float) = updateActiveLayer { it.copy(brightness = value) }
    fun onContrastChanged(value: Float) = updateActiveLayer { it.copy(contrast = value) }
    fun onSaturationChanged(value: Float) = updateActiveLayer { it.copy(saturation = value) }
    fun onColorBalanceRChanged(value: Float) = updateActiveLayer { it.copy(colorBalanceR = value) }
    fun onColorBalanceGChanged(value: Float) = updateActiveLayer { it.copy(colorBalanceG = value) }
    fun onColorBalanceBChanged(value: Float) = updateActiveLayer { it.copy(colorBalanceB = value) }

    fun onScaleChanged(value: Float) = updateActiveLayer { it.copy(scale = value) }
    fun onOffsetChanged(value: androidx.compose.ui.geometry.Offset) = updateActiveLayer { it.copy(offset = it.offset + value) }

    fun onRotationXChanged(value: Float) {
        updateActiveLayer { it.copy(rotationX = value) }
        _uiState.update { it.copy(activeRotationAxis = RotationAxis.X) }
    }

    fun onRotationYChanged(value: Float) {
        updateActiveLayer { it.copy(rotationY = value) }
        _uiState.update { it.copy(activeRotationAxis = RotationAxis.Y) }
    }

    fun onRotationZChanged(value: Float) {
        updateActiveLayer { it.copy(rotationZ = value) }
        _uiState.update { it.copy(activeRotationAxis = RotationAxis.Z) }
    }

    fun onCycleRotationAxis() {
        val nextAxis = when (_uiState.value.activeRotationAxis) {
            RotationAxis.X -> RotationAxis.Y
            RotationAxis.Y -> RotationAxis.Z
            RotationAxis.Z -> RotationAxis.X
        }
        _uiState.update { it.copy(activeRotationAxis = nextAxis, showRotationAxisFeedback = true) }

        // Auto-hide feedback after delay handled by UI component
    }

    fun onAdjustmentStart() {
        pushHistory()
        _uiState.update { it.copy(gestureInProgress = true) }
    }
    fun onAdjustmentEnd() = _uiState.update { it.copy(gestureInProgress = false) }

    // Helper
    private fun updateActiveLayer(transform: (Layer) -> Layer) {
        _uiState.update { state ->
            val id = state.activeLayerId ?: return@update state
            state.copy(layers = state.layers.map { if (it.id == id) transform(it) else it })
        }
    }

    // Stubs for interface compliance if needed
    fun setLayerTransform(scale: Float, offset: androidx.compose.ui.geometry.Offset, rx: Float, ry: Float, rz: Float) {}
    fun onFeedbackShown() { _uiState.update { it.copy(showRotationAxisFeedback = false) } }
    fun onDoubleTapHintDismissed() {}
    fun onOnboardingComplete(mode: Any) {}
    fun onDrawingPathFinished(path: List<androidx.compose.ui.geometry.Offset>) {}
    fun onColorClicked() = onBalanceClicked()
    fun onLayerWarpChanged(layerId: String, mesh: List<Float>) {}
    fun onCycleBlendMode() {}
    fun onLayerDuplicated(id: String) {}
    fun copyLayerModifications(id: String) {}
    fun pasteLayerModifications(id: String) {}
    fun onLayerRenamed(id: String, name: String) {}
}