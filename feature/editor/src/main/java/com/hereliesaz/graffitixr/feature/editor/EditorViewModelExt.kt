// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModelExt.kt
package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val writeMutex = Mutex()

/**
 * Extension for EditorViewModel to seamlessly integrate the UI-layer 2D drawing tools.
 */
fun EditorViewModel.applyStrokeToActiveLayer(stroke: List<Offset>, canvasSize: IntSize, slamManager: SlamManager) {
    val currentState = uiState.value
    val activeTool = currentState.activeTool

    if (activeTool == Tool.NONE || stroke.isEmpty()) return

    val activeLayerIndex = currentState.layers.indexOfFirst { it.id == currentState.activeLayerId }
    if (activeLayerIndex == -1) return

    val activeLayer = currentState.layers[activeLayerIndex]
    val bitmap = activeLayer.bitmap ?: return

    viewModelScope.launch {
        // Map Compose offsets back to raw bitmap pixels
        val mappedStroke = ImageProcessor.mapScreenToBitmap(
            stroke,
            canvasSize.width,
            canvasSize.height,
            bitmap.width,
            bitmap.height
        )

        // Offload pixel manipulation to background/C++ thread
        val processedBitmap = ImageProcessor.applyToolToBitmap(
            originalBitmap = bitmap,
            stroke = mappedStroke,
            tool = activeTool,
            brushSize = currentState.brushSize,
            brushColor = currentState.activeColor.toArgb(),
            intensity = 0.5f,
            slamManager = slamManager
        )

        // Persist the updated bitmap over its local file immediately
        val activeUri = activeLayer.uri
        if (activeUri != null) {
            val path = activeUri.path
            if (path != null) {
                withContext(Dispatchers.IO) {
                    try {
                        val file = java.io.File(path)
                        writeMutex.withLock {
                            java.io.FileOutputStream(file).use { out ->
                                processedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        val updatedLayers = currentState.layers.toMutableList().apply {
            this[activeLayerIndex] = activeLayer.copy(bitmap = processedBitmap)
        }

        updateLayersInternal(updatedLayers)
    }
}

private fun EditorViewModel.updateLayersInternal(newLayers: List<com.hereliesaz.graffitixr.common.model.Layer>) {
    this.setLayers(newLayers)
}