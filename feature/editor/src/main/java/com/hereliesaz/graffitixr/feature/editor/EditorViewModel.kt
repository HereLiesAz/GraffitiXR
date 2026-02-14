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
import com.hereliesaz.graffitixr.common.model.OverlayLayer
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.feature.editor.BackgroundRemover
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel for the Image Editor feature.
 * Manages layer composition, image adjustments, and transformation gestures.
 */
@OptIn(FlowPreview::class)
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

    private val undoStack = ArrayDeque<EditorUiState>()
    private val redoStack = ArrayDeque<EditorUiState>()
    private val maxStackSize = 20
    private var isAdjusting = false
    
    // Flag to prevent recursive saving when loading
    private var isRestoring = false

    init {
        // 1. Observe Project Changes (State Restoration)
        viewModelScope.launch(dispatchers.main) {
            projectRepository.currentProject.collectLatest { project ->
                if (project != null && !isRestoring) {
                    restoreProjectState(project)
                }
            }
        }

        // 2. Automatic Saving (Debounced)
        viewModelScope.launch(dispatchers.io) {
            _uiState
                .debounce(2000L) // Wait 2 seconds of inactivity
                .distinctUntilChanged { old, new -> 
                    // Only save if meaningful data changed (ignore transient UI flags)
                    old.layers == new.layers && 
                    old.backgroundImageUri == new.backgroundImageUri &&
                    old.mapPath == new.mapPath &&
                    old.isRightHanded == new.isRightHanded
                }
                .collect { 
                    if (!isRestoring) {
                        saveProject() 
                    }
                }
        }
    }

    private fun restoreProjectState(project: com.hereliesaz.graffitixr.common.model.GraffitiProject) {
        isRestoring = true
        viewModelScope.launch(dispatchers.io) {
            // Load Bitmaps for layers
            val restoredLayers = project.layers.mapNotNull { overlayLayer ->
                loadBitmapFromUri(overlayLayer.uri)?.let { bitmap ->
                    Layer(
                        id = overlayLayer.id,
                        name = overlayLayer.name,
                        bitmap = bitmap,
                        uri = overlayLayer.uri,
                        offset = overlayLayer.offset,
                        scale = overlayLayer.scale,
                        rotationX = overlayLayer.rotationX,
                        rotationY = overlayLayer.rotationY,
                        rotationZ = overlayLayer.rotationZ,
                        opacity = overlayLayer.opacity,
                        blendMode = overlayLayer.blendMode,
                        brightness = overlayLayer.brightness,
                        contrast = overlayLayer.contrast,
                        saturation = overlayLayer.saturation,
                        colorBalanceR = overlayLayer.colorBalanceR,
                        colorBalanceG = overlayLayer.colorBalanceG,
                        colorBalanceB = overlayLayer.colorBalanceB,
                        isImageLocked = overlayLayer.isImageLocked,
                        isVisible = overlayLayer.isVisible,
                        warpMesh = overlayLayer.warpMesh
                    )
                }
            }

            val backgroundBitmap = project.backgroundImageUri?.let { loadBitmapFromUri(it) }

            _uiState.update {
                it.copy(
                    layers = restoredLayers,
                    backgroundImageUri = project.backgroundImageUri?.toString(),
                    backgroundBitmap = backgroundBitmap,
                    mapPath = project.mapPath,
                    activeLayerId = restoredLayers.firstOrNull()?.id,
                    isRightHanded = project.isRightHanded
                )
            }
            isRestoring = false
        }
    }

    fun setEditorMode(mode: EditorMode) {
        _uiState.update { it.copy(editorMode = mode) }
    }

    private fun saveState() {
        if (undoStack.size >= maxStackSize) {
            undoStack.removeFirst()
        }
        undoStack.addLast(_uiState.value)
        redoStack.clear()
        _uiState.update { it.copy(canUndo = true, canRedo = false) }
    }

    fun onUndoClicked() {
        if (undoStack.isNotEmpty()) {
            val previousState = undoStack.removeLast()
            redoStack.addLast(_uiState.value)
            _uiState.value = previousState.copy(
                canUndo = undoStack.isNotEmpty(),
                canRedo = true
            )
        }
    }

    fun onRedoClicked() {
        if (redoStack.isNotEmpty()) {
            val nextState = redoStack.removeLast()
            undoStack.addLast(_uiState.value)
            _uiState.value = nextState.copy(
                canUndo = true,
                canRedo = redoStack.isNotEmpty()
            )
        }
    }

    fun onMagicClicked() {
        saveState()
        updateActiveLayer {
            it.copy(
                contrast = 1.2f,
                saturation = 1.3f,
                brightness = 0.1f
            )
        }
    }

    fun onAdjustmentStart() {
        if (!isAdjusting) {
            saveState()
            isAdjusting = true
        }
    }

    fun onAdjustmentEnd() {
        isAdjusting = false
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

    fun onOpacityChanged(v: Float) {
        updateActiveLayer { it.copy(opacity = v) }
    }

    fun onBrightnessChanged(v: Float) {
        updateActiveLayer { it.copy(brightness = v) }
    }

    fun onContrastChanged(v: Float) {
        updateActiveLayer { it.copy(contrast = v) }
    }

    fun onSaturationChanged(v: Float) {
        updateActiveLayer { it.copy(saturation = v) }
    }

    fun onColorBalanceRChanged(v: Float) {
        updateActiveLayer { it.copy(colorBalanceR = v) }
    }

    fun onColorBalanceGChanged(v: Float) {
        updateActiveLayer { it.copy(colorBalanceG = v) }
    }

    fun onColorBalanceBChanged(v: Float) {
        updateActiveLayer { it.copy(colorBalanceB = v) }
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
                    bitmap = bitmap,
                    uri = uri
                )
                _uiState.update {
                    it.copy(
                        layers = it.layers + newLayer,
                        activeLayerId = newLayer.id,
                        isImageLocked = newLayer.isImageLocked
                    )
                }
            }
        }
    }

    fun onLayerActivated(layerId: String) {
        _uiState.update { state ->
            val layer = state.layers.find { it.id == layerId }
            state.copy(
                activeLayerId = layerId,
                isImageLocked = layer?.isImageLocked ?: false
            )
        }
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
            val newActiveId = if (state.activeLayerId == layerId) null else state.activeLayerId
            val isLocked = if (newActiveId != null) {
                newLayers.find { it.id == newActiveId }?.isImageLocked ?: false
            } else {
                false
            }
            state.copy(layers = newLayers, activeLayerId = newActiveId, isImageLocked = isLocked)
        }
    }

    fun toggleImageLock() {
        updateActiveLayer { it.copy(isImageLocked = !it.isImageLocked) }
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
        onRotationChanged(rotation)
    }

    fun onRotationChanged(rotation: Float) {
        updateActiveLayer { layer ->
            when (_uiState.value.activeRotationAxis) {
                RotationAxis.X -> layer.copy(rotationX = layer.rotationX + rotation)
                RotationAxis.Y -> layer.copy(rotationY = layer.rotationY + rotation)
                RotationAxis.Z -> layer.copy(rotationZ = layer.rotationZ + rotation)
            }
        }
    }

    fun onTransformGesture(pan: Offset, zoom: Float, rotation: Float) {
        updateActiveLayer { layer ->
            val newScale = layer.scale * zoom
            val newOffset = layer.offset + pan
            
            val axis = _uiState.value.activeRotationAxis
            layer.copy(
                scale = newScale,
                offset = newOffset,
                rotationX = if (axis == RotationAxis.X) layer.rotationX + rotation else layer.rotationX,
                rotationY = if (axis == RotationAxis.Y) layer.rotationY + rotation else layer.rotationY,
                rotationZ = if (axis == RotationAxis.Z) layer.rotationZ + rotation else layer.rotationZ
            )
        }
    }

    fun onCycleRotationAxis() {
        _uiState.update {
            val nextAxis = when(it.activeRotationAxis) {
                RotationAxis.X -> RotationAxis.Y
                RotationAxis.Y -> RotationAxis.Z
                RotationAxis.Z -> RotationAxis.X
            }
            it.copy(
                activeRotationAxis = nextAxis,
                showRotationAxisFeedback = true
            )
        }
    }

    fun onFeedbackShown() {
        _uiState.update { it.copy(showRotationAxisFeedback = false) }
    }

    fun onGestureStart() {
        _uiState.update { it.copy(gestureInProgress = true) }
    }

    fun onGestureEnd() {
        _uiState.update { it.copy(gestureInProgress = false) }
    }

    fun setLayerTransform(scale: Float, offset: Offset, rx: Float, ry: Float, rz: Float) {
        updateActiveLayer { it.copy(scale = scale, offset = offset, rotationX = rx, rotationY = ry, rotationZ = rz) }
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
            // This now returns black lines on transparent background
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
        _uiState.update { 
            val nextPanel = if (it.activePanel == EditorPanel.ADJUST) EditorPanel.NONE else EditorPanel.ADJUST
            it.copy(activePanel = nextPanel)
        }
    }

    fun onColorClicked() {
        _uiState.update { 
            val nextPanel = if (it.activePanel == EditorPanel.COLOR) EditorPanel.NONE else EditorPanel.COLOR
            it.copy(activePanel = nextPanel)
        }
    }

    fun onDismissPanel() {
        _uiState.update { it.copy(activePanel = EditorPanel.NONE) }
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

    fun saveProject(name: String? = null) {
        viewModelScope.launch(dispatchers.io) {
            val currentState = _uiState.value
            val currentProject = projectRepository.currentProject.value
            
            val overlayLayers = currentState.layers.map { layer ->
                OverlayLayer(
                    id = layer.id,
                    name = layer.name,
                    uri = layer.uri,
                    scale = layer.scale,
                    offset = layer.offset,
                    rotationX = layer.rotationX,
                    rotationY = layer.rotationY,
                    rotationZ = layer.rotationZ,
                    opacity = layer.opacity,
                    blendMode = layer.blendMode,
                    brightness = layer.brightness,
                    contrast = layer.contrast,
                    saturation = layer.saturation,
                    colorBalanceR = layer.colorBalanceR,
                    colorBalanceG = layer.colorBalanceG,
                    colorBalanceB = layer.colorBalanceB,
                    isImageLocked = layer.isImageLocked,
                    isVisible = layer.isVisible,
                    warpMesh = layer.warpMesh
                )
            }

            // 1. Save Native World Map
            val mapPath = currentState.mapPath
            if (mapPath != null) {
                slamManager.saveWorld(mapPath)
            }

            if (currentProject != null) {
                val updatedProject = currentProject.copy(
                    name = name ?: currentProject.name,
                    layers = overlayLayers,
                    backgroundImageUri = if (currentState.backgroundImageUri != null) Uri.parse(currentState.backgroundImageUri) else null,
                    mapPath = currentState.mapPath,
                    isRightHanded = currentState.isRightHanded,
                    lastModified = System.currentTimeMillis()
                )
                projectRepository.updateProject(updatedProject)
            } else {
                // Create new if none exists
                val newProject = com.hereliesaz.graffitixr.common.model.GraffitiProject(
                    name = name ?: "New Project ${System.currentTimeMillis()}",
                    layers = overlayLayers,
                    backgroundImageUri = if (currentState.backgroundImageUri != null) Uri.parse(currentState.backgroundImageUri) else null,
                    mapPath = currentState.mapPath,
                    isRightHanded = currentState.isRightHanded
                )
                projectRepository.createProject(newProject)
            }
        }
    }

    // --- Utils ---

    private fun updateActiveLayer(transform: (Layer) -> Layer) {
        _uiState.update { state ->
            val activeId = state.activeLayerId ?: return@update state
            var isLocked = state.isImageLocked
            val newLayers = state.layers.map {
                if (it.id == activeId) {
                    val newLayer = transform(it)
                    isLocked = newLayer.isImageLocked
                    newLayer
                } else it
            }
            state.copy(layers = newLayers, isImageLocked = isLocked)
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