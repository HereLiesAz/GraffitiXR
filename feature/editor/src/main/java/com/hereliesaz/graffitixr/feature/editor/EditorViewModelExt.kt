package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Extension for EditorViewModel to seamlessly integrate the UI-layer 2D drawing tools.
 * Keeps the core ViewModel clean while routing stroke data to the ImageProcessor.
 */
fun EditorViewModel.applyStrokeToActiveLayer(stroke: List<Offset>) {
    val currentState = uiState.value
    val activeTool = currentState.activeTool

    if (activeTool == Tool.NONE || stroke.isEmpty()) return

    val activeLayerIndex = currentState.layers.indexOfFirst { it.id == currentState.activeLayerId }
    if (activeLayerIndex == -1) return

    val activeLayer = currentState.layers[activeLayerIndex]
    val bitmap = activeLayer.bitmap ?: return

    viewModelScope.launch {
        // Offload pixel manipulation to the background thread entirely within the Kotlin domain
        val processedBitmap = ImageProcessor.applyToolToBitmap(
            originalBitmap = bitmap,
            stroke = stroke,
            tool = activeTool
        )

        // Update the state flow with the newly mutated bitmap
        val updatedLayers = currentState.layers.toMutableList().apply {
            this[activeLayerIndex] = activeLayer.copy(bitmap = processedBitmap)
        }

        // You will likely need to cast or access the private MutableStateFlow here
        // depending on your exact EditorViewModel visibility, or proxy it through a public method.
        updateLayersInternal(updatedLayers)
    }
}

/**
 * Helper to update the UI State layers.
 * Ensure your EditorViewModel exposes a method to accept this new list.
 */
private fun EditorViewModel.updateLayersInternal(newLayers: List<com.hereliesaz.graffitixr.common.model.Layer>) {
    // Assuming you have a public method like setLayers(layers) or similar.
    // If not, add `fun setLayers(layers: List<Layer>)` to EditorViewModel.
    this.javaClass.methods.find { it.name == "setLayers" }?.invoke(this, newLayers)
}