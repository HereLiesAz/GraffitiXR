package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.OverlayLayer
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.common.util.ImageUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class EditorViewModel : ViewModel(), EditorActions {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private fun updateActiveLayer(update: (OverlayLayer) -> OverlayLayer) {
        _uiState.update { state ->
            val layers = state.layers.map { layer ->
                if (layer.id == state.activeLayerId) update(layer) else layer
            }
            state.copy(layers = layers)
        }
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
        // TODO: Implement undo logic
    }

    override fun onRedoClicked() {
        // TODO: Implement redo logic
    }

    override fun onMagicClicked() {
        // TODO: Implement magic alignment logic
    }

    override fun onRemoveBackgroundClicked() {
        // TODO: Implement background removal
    }

    override fun onLineDrawingClicked() {
        _uiState.update { it.copy(editorMode = EditorMode.DRAW) }
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
        _uiState.update { it.copy(activeLayerId = id) }
    }

    override fun onLayerRenamed(id: String, name: String) {
        _uiState.update { state ->
            val layers = state.layers.map { if (it.id == id) it.copy(name = name) else it }
            state.copy(layers = layers)
        }
    }

    override fun onLayerReordered(newOrder: List<String>) {
        _uiState.update { state ->
            val reordered = newOrder.mapNotNull { id -> state.layers.find { it.id == id } }
            state.copy(layers = reordered)
        }
    }

    override fun onLayerDuplicated(id: String) {
        // TODO: Implement duplication
    }

    override fun onLayerRemoved(id: String) {
        _uiState.update { state ->
            state.copy(layers = state.layers.filterNot { it.id == id })
        }
    }

    override fun copyLayerModifications(id: String) {
        // TODO
    }

    override fun pasteLayerModifications(id: String) {
        // TODO
    }

    override fun onScaleChanged(s: Float) {
        updateActiveLayer { it.copy(scale = s) }
    }

    override fun onOffsetChanged(o: Offset) {
        updateActiveLayer { it.copy(offset = o) }
    }

    override fun onRotationXChanged(d: Float) {
        updateActiveLayer { it.copy(rotationX = d) }
    }

    override fun onRotationYChanged(d: Float) {
        updateActiveLayer { it.copy(rotationY = d) }
    }

    override fun onRotationZChanged(d: Float) {
        updateActiveLayer { it.copy(rotationZ = d) }
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
        _uiState.update { it.copy(gestureInProgress = true) }
    }

    override fun onGestureEnd() {
        _uiState.update { it.copy(gestureInProgress = false) }
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
    }
}
