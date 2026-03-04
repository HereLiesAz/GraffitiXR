// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModelExt.kt
package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.toArgb

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
            tool = activeTool,
            brushSize = currentState.brushSize,
            brushColor = currentState.activeColor.toArgb()
        )

        // Persist the updated bitmap over its local file immediately so manual saves aren't needed
        val activeUri = activeLayer.uri
        if (activeUri != null) {
            val path = activeUri.path
            if (path != null) {
                withContext(Dispatchers.IO) {
                    try {
                        val file = java.io.File(path)
                        java.io.FileOutputStream(file).use { out ->
                            processedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        // Update the state flow with the newly mutated bitmap
        val updatedLayers = currentState.layers.toMutableList().apply {
            this[activeLayerIndex] = activeLayer.copy(bitmap = processedBitmap)
        }

        updateLayersInternal(updatedLayers)
    }
}

/**
 * Helper to update the UI State layers.
 */
private fun EditorViewModel.updateLayersInternal(newLayers: List<com.hereliesaz.graffitixr.common.model.Layer>) {
    this.setLayers(newLayers)
}