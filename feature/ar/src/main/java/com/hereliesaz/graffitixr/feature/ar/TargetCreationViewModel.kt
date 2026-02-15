package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.feature.ar.computervision.TargetEvolutionEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TargetEvolutionState(
    val originalImage: Bitmap? = null,
    val maskImage: Bitmap? = null,
    val isProcessing: Boolean = false,
    val mode: EvolutionMode = EvolutionMode.ADD,
    val derivedCorners: List<Offset> = emptyList()
)

enum class EvolutionMode {
    ADD, SUBTRACT
}

@HiltViewModel
class TargetCreationViewModel @Inject constructor(
    private val evolutionEngine: TargetEvolutionEngine,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(TargetEvolutionState())
    val uiState = _uiState.asStateFlow()

    fun setImage(bitmap: Bitmap) {
        _uiState.update { it.copy(originalImage = bitmap, isProcessing = true) }
        viewModelScope.launch(dispatchers.default) {
            try {
                // Initial Guess
                val initialMask = evolutionEngine.initialGuess(bitmap)
                _uiState.update { it.copy(maskImage = initialMask, isProcessing = false) }
                recalculateCorners()
            } catch (e: Exception) {
                _uiState.update { it.copy(isProcessing = false) }
            }
        }
    }

    fun onTouch(relativePos: Offset, viewWidth: Int, viewHeight: Int) {
        val state = _uiState.value
        val image = state.originalImage ?: return
        val mask = state.maskImage ?: return
        if (state.isProcessing) return

        // Scale touch to bitmap coordinates
        val scaleX = image.width.toFloat() / viewWidth
        val scaleY = image.height.toFloat() / viewHeight
        val bitmapPoint = Offset(relativePos.x * scaleX, relativePos.y * scaleY)

        _uiState.update { it.copy(isProcessing = true) }

        viewModelScope.launch(dispatchers.default) {
            val isAdding = state.mode == EvolutionMode.ADD
            val newMask = evolutionEngine.refineMask(image, mask, bitmapPoint, isAdding)

            _uiState.update { it.copy(maskImage = newMask, isProcessing = false) }
            recalculateCorners()
        }
    }

    private fun recalculateCorners() {
        val mask = _uiState.value.maskImage ?: return
        viewModelScope.launch(dispatchers.default) {
            val corners = evolutionEngine.extractCorners(mask)
            _uiState.update { it.copy(derivedCorners = corners) }
        }
    }

    fun setMode(mode: EvolutionMode) {
        _uiState.update { it.copy(mode = mode) }
    }
}