// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModelExt.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Tool

/**
 * Extension for EditorViewModel to seamlessly integrate the UI-layer 2D drawing tools.
 * Handles the extraction of UI bounds and routes the stroke into the Action command queue.
 */
fun EditorViewModel.applyStrokeToActiveLayer(stroke: List<Offset>, canvasSize: IntSize) {
    val currentState = uiState.value
    val activeTool = currentState.activeTool

    if (activeTool == Tool.NONE || stroke.isEmpty()) return

    val activeLayerId = currentState.activeLayerId ?: return
    val activeLayer = currentState.layers.find { it.id == activeLayerId } ?: return
    val bitmap = activeLayer.bitmap ?: return

    val command = StrokeCommand(
        path = stroke,
        canvasSize = canvasSize,
        tool = activeTool,
        brushSize = currentState.brushSize,
        brushColor = currentState.activeColor.toArgb(),
        intensity = 0.5f
    )

    processNewStroke(activeLayerId, bitmap, command, activeLayer)
}