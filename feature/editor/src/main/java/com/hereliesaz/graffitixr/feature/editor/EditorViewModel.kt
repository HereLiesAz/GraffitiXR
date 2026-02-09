package com.hereliesaz.graffitixr.feature.editor

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.OverlayLayer
import com.hereliesaz.graffitixr.common.model.RotationAxis
import android.app.Application
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import com.hereliesaz.graffitixr.common.util.ImageUtils
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val application: Application,
    private val settingsRepository: SettingsRepository,
    private val projectRepository: ProjectRepository
) : ViewModel(), EditorActions {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val undoStack = ArrayDeque<EditorUiState>()
    private val redoStack = ArrayDeque<EditorUiState>()
    private val maxStackSize = 20
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            launch {
                settingsRepository.isRightHanded.collectLatest { isRight ->
                    _uiState.update { it.copy(isRightHanded = isRight) }
                }
            }
            launch {
                projectRepository.currentProject.collectLatest { project ->
                    if (project != null) {
                        _uiState.update { state ->
                            state.copy(
                                layers = project.layers,
                                backgroundImageUri = project.backgroundImageUri,
                                // Assuming we only sync layers and BG for now to avoid overwriting transient UI state like activePanel
                            )
                        }
                    }
                }
            }
        }
    }

    private fun persistState() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(1000) // Debounce 1s to avoid excessive disk writes
            projectRepository.updateProject { project ->
                project.copy(
                    layers = _uiState.value.layers,
                    backgroundImageUri = _uiState.value.backgroundImageUri
                )
            }
        }
    }

    private fun saveState() {
        if (undoStack.size >= maxStackSize) {
            undoStack.removeFirst()
        }
        undoStack.addLast(_uiState.value)
        redoStack.clear()
    }

    private fun updateActiveLayer(update: (OverlayLayer) -> OverlayLayer) {
        saveState()
        _uiState.update { state ->
            val layers = state.layers.map { layer ->
                if (layer.id == state.activeLayerId) update(layer) else layer
            }
            state.copy(layers = layers)
        }
        persistState()
    }

    override fun onOpacityChanged(v: Float) {
        updateActiveLayer { it.copy(opacity = v) }
    }

    override fun onBrightnessChanged(v: Float) {
        updateActiveLayer { it.copy(brightness = v) }
    }

    override fun onContrastChanged(v: Float) {
        updateActiveLayer { it.copy(contrast = v) }
    }

    override fun onSaturationChanged(v: Float) {
        updateActiveLayer { it.copy(saturation = v) }
    }

    override fun onColorBalanceRChanged(v: Float) {
        updateActiveLayer { it.copy(colorBalanceR = v) }
    }

    override fun onColorBalanceGChanged(v: Float) {
        updateActiveLayer { it.copy(colorBalanceG = v) }
    }

    override fun onColorBalanceBChanged(v: Float) {
        updateActiveLayer { it.copy(colorBalanceB = v) }
    }

    override fun onUndoClicked() {
        if (undoStack.isNotEmpty()) {
            val previousState = undoStack.removeLast()
            redoStack.addLast(_uiState.value)
            _uiState.value = previousState
            persistState()
        }
    }

    override fun onRedoClicked() {
        if (redoStack.isNotEmpty()) {
            val nextState = redoStack.removeLast()
            undoStack.addLast(_uiState.value)
            _uiState.value = nextState
            persistState()
        }
    }

    override fun onMagicClicked() {
        saveState()
        updateActiveLayer {
            it.copy(
                contrast = 1.2f,
                saturation = 1.3f,
                brightness = 0.1f
            )
        }
    }

    override fun onRemoveBackgroundClicked() {
        val activeLayer = _uiState.value.activeLayer ?: return
        viewModelScope.launch {
            saveState()
            val bitmap = BitmapUtils.getBitmapFromUri(application, activeLayer.uri) ?: return@launch
            val processedBitmap = BackgroundRemover.removeBackground(application, bitmap)

            if (processedBitmap != null) {
                val newUri = saveBitmapToCache(processedBitmap)
                updateActiveLayer { it.copy(uri = newUri) }
            }
        }
    }

    private fun saveBitmapToCache(bitmap: Bitmap): Uri {
        val filename = "processed_${UUID.randomUUID()}.png"
        val file = File(application.cacheDir, filename)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return Uri.fromFile(file)
    }

    override fun onLineDrawingClicked() {
        val activeLayer = _uiState.value.activeLayer ?: return
        viewModelScope.launch {
            saveState()
            val bitmap = BitmapUtils.getBitmapFromUri(application, activeLayer.uri) ?: return@launch
            val processedBitmap = ImageProcessor.detectEdges(bitmap)

            if (processedBitmap != null) {
                val newUri = saveBitmapToCache(processedBitmap)
                updateActiveLayer { it.copy(uri = newUri) }
            }
        }
    }

    fun setEditorMode(mode: EditorMode) {
        _uiState.update { it.copy(editorMode = mode) }
    }

    fun setBackgroundImage(uri: Uri) {
        saveState()
        _uiState.update { it.copy(backgroundImageUri = uri, isEditingBackground = true) }
        persistState()
    }


    fun toggleHandedness() {
        viewModelScope.launch {
            val current = _uiState.value.isRightHanded
            settingsRepository.setRightHanded(!current)
        }
    }

    // IMPL: Cycle Blend Mode
    override fun onCycleBlendMode() {
        updateActiveLayer { layer ->
            val nextModeName = ImageUtils.getNextBlendMode(layer.blendMode.toString())
            val nextMode = when (nextModeName) {
                "SrcOver" -> androidx.compose.ui.graphics.BlendMode.SrcOver
                "Multiply" -> androidx.compose.ui.graphics.BlendMode.Multiply
                "Screen" -> androidx.compose.ui.graphics.BlendMode.Screen
                "Overlay" -> androidx.compose.ui.graphics.BlendMode.Overlay
                "Darken" -> androidx.compose.ui.graphics.BlendMode.Darken
                "Lighten" -> androidx.compose.ui.graphics.BlendMode.Lighten
                "ColorDodge" -> androidx.compose.ui.graphics.BlendMode.ColorDodge
                "ColorBurn" -> androidx.compose.ui.graphics.BlendMode.ColorBurn
                "Difference" -> androidx.compose.ui.graphics.BlendMode.Difference
                "Exclusion" -> androidx.compose.ui.graphics.BlendMode.Exclusion
                "Hue" -> androidx.compose.ui.graphics.BlendMode.Hue
                "Saturation" -> androidx.compose.ui.graphics.BlendMode.Saturation
                "Color" -> androidx.compose.ui.graphics.BlendMode.Color
                "Luminosity" -> androidx.compose.ui.graphics.BlendMode.Luminosity
                else -> androidx.compose.ui.graphics.BlendMode.SrcOver
            }
            layer.copy(blendMode = nextMode)
        }
    }

    override fun toggleImageLock() {
        _uiState.update { it.copy(isImageLocked = !it.isImageLocked) }
    }

    override fun onLayerActivated(id: String) {
        _uiState.update { it.copy(activeLayerId = id, isEditingBackground = false) }
    }

    override fun onLayerRenamed(id: String, name: String) {
        saveState()
        _uiState.update { state ->
            val layers = state.layers.map { if (it.id == id) it.copy(name = name) else it }
            state.copy(layers = layers)
        }
        persistState()
    }

    override fun onLayerReordered(newOrder: List<String>) {
        saveState()
        _uiState.update { state ->
            val reordered = newOrder.mapNotNull { id -> state.layers.find { it.id == id } }
            state.copy(layers = reordered)
        }
        persistState()
    }

    override fun onLayerDuplicated(id: String) {
        // TODO: Implement duplication
    }

    override fun onLayerRemoved(id: String) {
        saveState()
        _uiState.update { state ->
            state.copy(layers = state.layers.filterNot { it.id == id })
        }
        persistState()
    }

    override fun onAddLayer(uri: Uri) {
        saveState()
        val newLayer = OverlayLayer(
            id = UUID.randomUUID().toString(),
            name = "Layer ${_uiState.value.layers.size + 1}",
            uri = uri
        )
        _uiState.update { state ->
            state.copy(
                layers = state.layers + newLayer,
                activeLayerId = newLayer.id,
                isEditingBackground = false
            )
        }
        persistState()
    }

    override fun copyLayerModifications(id: String) {
        // TODO
    }

    override fun pasteLayerModifications(id: String) {
        // TODO
    }

    override fun onScaleChanged(s: Float) {
        if (_uiState.value.isEditingBackground) {
            _uiState.update { it.copy(backgroundScale = it.backgroundScale * s) }
        } else {
            _uiState.update { state ->
                val layers = state.layers.map { layer ->
                    if (layer.id == state.activeLayerId) layer.copy(scale = layer.scale * s) else layer
                }
                state.copy(layers = layers)
            }
        }
        // Debounced persist handled by persistState if called, but updateActiveLayer is NOT called here.
        // We should call persistState here too if we want to save geometry changes.
        // But onScaleChanged is continuous. persistState handles debounce.
        persistState()
    }

    override fun onOffsetChanged(o: Offset) {
        if (_uiState.value.isEditingBackground) {
            _uiState.update { it.copy(backgroundOffset = it.backgroundOffset + o) }
        } else {
             _uiState.update { state ->
                val layers = state.layers.map { layer ->
                    if (layer.id == state.activeLayerId) layer.copy(offset = layer.offset + o) else layer
                }
                state.copy(layers = layers)
            }
        }
        persistState()
    }

    override fun onRotationXChanged(d: Float) {
        _uiState.update { state ->
            val layers = state.layers.map { layer ->
                if (layer.id == state.activeLayerId) layer.copy(rotationX = layer.rotationX + d) else layer
            }
            state.copy(layers = layers)
        }
        persistState()
    }

    override fun onRotationYChanged(d: Float) {
        _uiState.update { state ->
            val layers = state.layers.map { layer ->
                if (layer.id == state.activeLayerId) layer.copy(rotationY = layer.rotationY + d) else layer
            }
            state.copy(layers = layers)
        }
        persistState()
    }

    override fun onRotationZChanged(d: Float) {
        _uiState.update { state ->
            val layers = state.layers.map { layer ->
                if (layer.id == state.activeLayerId) layer.copy(rotationZ = layer.rotationZ + d) else layer
            }
            state.copy(layers = layers)
        }
        persistState()
    }

    override fun onCycleRotationAxis() {
        _uiState.update { state ->
            val next = when (state.activeRotationAxis) {
                RotationAxis.X -> RotationAxis.Y
                RotationAxis.Y -> RotationAxis.Z
                RotationAxis.Z -> RotationAxis.X
            }
            state.copy(activeRotationAxis = next, showRotationAxisFeedback = true)
        }
    }

    override fun onGestureStart() {
        saveState()
        _uiState.update { it.copy(gestureInProgress = true) }
    }

    override fun onGestureEnd() {
        _uiState.update { it.copy(gestureInProgress = false) }
        persistState()
    }

    override fun setLayerTransform(scale: Float, offset: Offset, rx: Float, ry: Float, rz: Float) {
        updateActiveLayer { it.copy(scale = scale, offset = offset, rotationX = rx, rotationY = ry, rotationZ = rz) }
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
        _uiState.update { it.copy(drawingPaths = it.drawingPaths + listOf(path)) }
        persistState()
    }

    override fun onAdjustClicked() {
        _uiState.update {
            if (it.activePanel == EditorPanel.ADJUST) it.copy(activePanel = EditorPanel.NONE)
            else it.copy(activePanel = EditorPanel.ADJUST)
        }
    }

    override fun onColorClicked() {
        _uiState.update {
            if (it.activePanel == EditorPanel.COLOR) it.copy(activePanel = EditorPanel.NONE)
            else it.copy(activePanel = EditorPanel.COLOR)
        }
    }

    override fun onDismissPanel() {
        _uiState.update { it.copy(activePanel = EditorPanel.NONE) }
    }
}
