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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Tool

@Composable
fun DrawingCanvas(
    activeTool: Tool,
    brushSize: Float,
    activeColor: Color,
    // Identity of the current layer bitmap. When this changes, the committed
    // preview path is cleared because the bitmap has been updated.
    layerBitmapKey: Any?,
    modifier: Modifier = Modifier,
    onPathFinished: (List<Offset>, Tool, IntSize) -> Unit
) {
    // Live path being drawn right now
    var currentPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    // Path that was committed but bitmap hasn't updated yet — kept visible to prevent flicker
    var pendingPath by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Once the layer bitmap updates (new object identity), the bitmap already
    // contains the stroke — clear the overlay path.
    LaunchedEffect(layerBitmapKey) {
        pendingPath = emptyList()
    }

    // Also clear pending when tool changes so stale previews don't linger
    LaunchedEffect(activeTool) {
        pendingPath = emptyList()
        currentPoints = emptyList()
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
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
                            // Keep the path visible until the bitmap catches up
                            pendingPath = currentPoints
                            onPathFinished(currentPoints, activeTool, canvasSize)
                        }
                        currentPoints = emptyList()
                    }
                )
            }
    ) {
        // Show live path while drawing; fall back to committed path while bitmap processes
        val displayPath = if (currentPoints.isNotEmpty()) currentPoints else pendingPath
        if (displayPath.isEmpty()) return@Canvas

        val path = Path().apply {
            moveTo(displayPath.first().x, displayPath.first().y)
            for (i in 1 until displayPath.size) {
                lineTo(displayPath[i].x, displayPath[i].y)
            }
        }

        val stroke = Stroke(width = brushSize, cap = StrokeCap.Round, join = StrokeJoin.Round)

        when (activeTool) {
            Tool.BRUSH -> drawPath(
                path = path,
                color = activeColor,
                style = stroke,
                blendMode = BlendMode.SrcOver
            )
            Tool.ERASER -> drawPath(
                path = path,
                color = Color.White.copy(alpha = 0.35f),
                style = stroke,
                blendMode = BlendMode.SrcOver
            )
            Tool.BLUR -> drawPath(
                path = path,
                color = Color.Gray.copy(alpha = 0.30f),
                style = stroke,
                blendMode = BlendMode.SrcOver
            )
            Tool.DODGE -> drawPath(
                path = path,
                color = Color.White.copy(alpha = 0.30f),
                style = stroke,
                blendMode = BlendMode.SrcOver
            )
            Tool.BURN -> drawPath(
                path = path,
                color = Color.Black.copy(alpha = 0.30f),
                style = stroke,
                blendMode = BlendMode.SrcOver
            )
            Tool.HEAL -> drawPath(
                path = path,
                color = Color.Cyan.copy(alpha = 0.30f),
                style = stroke,
                blendMode = BlendMode.SrcOver
            )
            Tool.LIQUIFY -> drawPath(
                path = path,
                color = Color.Magenta.copy(alpha = 0.25f),
                style = stroke,
                blendMode = BlendMode.SrcOver
            )
            else -> {}
        }
    }
}
