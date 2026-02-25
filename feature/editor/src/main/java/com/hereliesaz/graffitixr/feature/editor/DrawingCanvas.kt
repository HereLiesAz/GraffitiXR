package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.hereliesaz.graffitixr.common.model.Tool

@Composable
fun DrawingCanvas(
    activeTool: Tool,
    brushSize: Float,
    activeColor: Color,
    onPathFinished: (List<Offset>, Tool) -> Unit
) {
    var currentPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(activeTool) {
                if (activeTool == Tool.NONE) return@pointerInput

                detectDragGestures(
                    onDragStart = { offset ->
                        currentPoints = listOf(offset)
                    },
                    onDrag = { change, _ ->
                        currentPoints = currentPoints + change.position
                    },
                    onDragEnd = {
                        if (currentPoints.isNotEmpty()) {
                            onPathFinished(currentPoints, activeTool)
                        }
                        currentPoints = emptyList()
                    }
                )
            }
    ) {
        if (currentPoints.isNotEmpty() && (activeTool == Tool.BRUSH || activeTool == Tool.ERASER)) {
            val path = Path()
            path.moveTo(currentPoints.first().x, currentPoints.first().y)
            for (i in 1 until currentPoints.size) {
                path.lineTo(currentPoints[i].x, currentPoints[i].y)
            }

            drawPath(
                path = path,
                color = if (activeTool == Tool.ERASER) Color.Transparent else activeColor,
                style = Stroke(width = brushSize, cap = StrokeCap.Round, join = StrokeJoin.Round),
                blendMode = if (activeTool == Tool.ERASER) BlendMode.Clear else BlendMode.SrcOver
            )
        }
    }
}