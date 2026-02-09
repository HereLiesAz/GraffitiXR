package com.hereliesaz.graffitixr.feature.editor

import androidx.lifecycle.ViewModel
import com.hereliesaz.graffitixr.core.data.manager.ProjectManager
import com.hereliesaz.graffitixr.core.model.Layer
import com.hereliesaz.graffitixr.core.model.LayerType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val projectManager: ProjectManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    /**
     * Adds a new layer to the current project state.
     * Defaults to a Raster layer with full opacity.
     */
    fun onAddLayer() {
        val newLayer = Layer(
            id = UUID.randomUUID().toString(),
            name = "Layer ${_uiState.value.layers.size + 1}",
            isVisible = true,
            type = LayerType.RASTER,
            opacity = 1.0f
        )

        _uiState.update { currentState ->
            val updatedLayers = currentState.layers + newLayer
            currentState.copy(
                layers = updatedLayers,
                selectedLayerId = newLayer.id
            )
        }

        // In a real scenario, we would trigger a save to the ProjectManager here
        // projectManager.saveLayer(newLayer)
    }

    /**
     * Sets the currently active layer for editing.
     */
    fun onSelectLayer(layerId: String) {
        _uiState.update { it.copy(selectedLayerId = layerId) }
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

data class EditorUiState(
    val layers: List<Layer> = emptyList(),
    val selectedLayerId: String? = null,
    val isToolsMenuOpen: Boolean = false
)