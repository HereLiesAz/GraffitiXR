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

    override fun onOpacityChanged(opacity: Float) {
        updateActiveLayer { it.copy(opacity = opacity) }
    }

    override fun onBrightnessChanged(brightness: Float) {
        updateActiveLayer { it.copy(brightness = brightness) }
    }

    override fun onContrastChanged(contrast: Float) {
        updateActiveLayer { it.copy(contrast = contrast) }
    }

    override fun onSaturationChanged(saturation: Float) {
        updateActiveLayer { it.copy(saturation = saturation) }
    }

    override fun onColorBalanceRChanged(value: Float) {
        updateActiveLayer { it.copy(colorBalanceR = value) }
    }

    override fun onColorBalanceGChanged(value: Float) {
        updateActiveLayer { it.copy(colorBalanceG = value) }
    }

    override fun onColorBalanceBChanged(value: Float) {
        updateActiveLayer { it.copy(colorBalanceB = value) }
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

    override fun onFeedbackShown() {
        _uiState.update { it.copy(showRotationAxisFeedback = false) }
    }

    override fun onOnboardingComplete(mode: EditorMode) {
        _uiState.update { it.copy(showOnboardingDialogForMode = null) }
    }

    override fun onDoubleTapHintDismissed() {
        _uiState.update { it.copy(showDoubleTapHint = false) }
    }

    override fun onDrawingPathFinished(path: List<Offset>) {
        _uiState.update { it.copy(drawingPaths = it.drawingPaths + listOf(path)) }
    }

    override fun onLineDrawingClicked() {
        _uiState.update { it.copy(editorMode = EditorMode.DRAW) }
    }

    // IMPL: Cycle Blend Mode
    override fun onCycleBlendMode() {
        updateActiveLayer { layer ->
            val nextModeName = ImageUtils.getNextBlendMode(layer.blendMode.toString())
            val nextMode = try {
                // Compose BlendMode is an internal-like class, valueOf doesn't exist.
                // We should ideally map from string or use a custom enum.
                // For now, let's use a simple mapping or fallback.
                when (nextModeName) {
                    "SrcOver" -> androidx.compose.ui.graphics.BlendMode.SrcOver
                    "Multiply" -> androidx.compose.ui.graphics.BlendMode.Multiply
                    "Screen" -> androidx.compose.ui.graphics.BlendMode.Screen
                    "Overlay" -> androidx.compose.ui.graphics.BlendMode.Overlay
                    "Darken" -> androidx.compose.ui.graphics.BlendMode.Darken
                    "Lighten" -> androidx.compose.ui.graphics.BlendMode.Lighten
                    "ColorDodge" -> androidx.compose.ui.graphics.BlendMode.ColorDodge
                    "ColorBurn" -> androidx.compose.ui.graphics.BlendMode.ColorBurn
                    "HardLight" -> androidx.compose.ui.graphics.BlendMode.HardLight
                    "SoftLight" -> androidx.compose.ui.graphics.BlendMode.SoftLight
                    "Difference" -> androidx.compose.ui.graphics.BlendMode.Difference
                    "Exclusion" -> androidx.compose.ui.graphics.BlendMode.Exclusion
                    "Hue" -> androidx.compose.ui.graphics.BlendMode.Hue
                    "Saturation" -> androidx.compose.ui.graphics.BlendMode.Saturation
                    "Color" -> androidx.compose.ui.graphics.BlendMode.Color
                    "Luminosity" -> androidx.compose.ui.graphics.BlendMode.Luminosity
                    else -> androidx.compose.ui.graphics.BlendMode.SrcOver
                }
            } catch (e: Exception) {
                androidx.compose.ui.graphics.BlendMode.SrcOver
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
}
