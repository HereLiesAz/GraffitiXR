package com.hereliesaz.graffitixr.feature.editor

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.hereliesaz.graffitixr.data.ProjectManager
import com.hereliesaz.graffitixr.common.model.OverlayLayer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val projectRepository: ProjectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    /**
     * Adds a new layer to the current project state.
     * Defaults to a Raster layer with full opacity.
     */
    fun onAddLayer() {
        val newLayer = OverlayLayer(
            id = UUID.randomUUID().toString(),
            name = "Layer ${_uiState.value.layers.size + 1}",
            isVisible = true,
            uri = Uri.EMPTY,
            opacity = 1.0f
        )

        _uiState.update { currentState ->
            val updatedLayers = currentState.layers + newLayer
            currentState.copy(
                layers = updatedLayers,
                activeLayerId = newLayer.id
            )
        }

        // In a real scenario, we would trigger a save to the ProjectManager here
        // projectRepository.saveLayer(newLayer)
    }

    /**
     * Sets the currently active layer for editing.
     */
    fun onSelectLayer(layerId: String) {
        _uiState.update { it.copy(activeLayerId = layerId) }
    }

    /**
     * Toggles the visibility boolean of a specific layer.
     */
    fun onToggleLayerVisibility(layerId: String) {
        _uiState.update { state ->
            val updatedLayers = state.layers.map { layer ->
                if (layer.id == layerId) {
                    layer.copy(isVisible = !layer.isVisible)
                } else {
                    layer
                }
            }
            state.copy(layers = updatedLayers)
        }
    }
}
