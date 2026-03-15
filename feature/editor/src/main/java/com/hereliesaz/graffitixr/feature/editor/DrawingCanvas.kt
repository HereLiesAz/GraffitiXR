package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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
    layerBitmapKey: Any?,
    modifier: Modifier = Modifier,
    onStrokeStart: (Offset, IntSize) -> Unit,
    onStrokePoint: (Offset) -> Unit,
    onStrokeEnd: () -> Unit,
) {
    // For Liquify only: collect points and show a fake preview since it can't render incrementally.
    var liquifyPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var liquifyPending by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // When the layer bitmap updates (stroke committed), clear Liquify pending path.
    LaunchedEffect(layerBitmapKey) {
        liquifyPending = emptyList()
    }

    LaunchedEffect(activeTool) {
        liquifyPoints = emptyList()
        liquifyPending = emptyList()
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            .pointerInput(activeTool) {
                if (activeTool == Tool.NONE) return@pointerInput

                detectDragGestures(
                    onDragStart = { offset ->
                        if (activeTool == Tool.LIQUIFY) {
                            liquifyPoints = listOf(offset)
                            liquifyPending = emptyList()
                        }
                        onStrokeStart(offset, canvasSize)
                    },
                    onDrag = { change, _ ->
                        if (activeTool == Tool.LIQUIFY) {
                            liquifyPoints = liquifyPoints + change.position
                        }
                        onStrokePoint(change.position)
                    },
                    onDragEnd = {
                        if (activeTool == Tool.LIQUIFY && liquifyPoints.isNotEmpty()) {
                            liquifyPending = liquifyPoints
                            liquifyPoints = emptyList()
                        }
                        onStrokeEnd()
                    }
                )
            }
    ) {
        // Only render a fake preview for Liquify — all other tools render directly into the layer bitmap.
        val displayPath = when {
            activeTool == Tool.LIQUIFY && liquifyPoints.isNotEmpty() -> liquifyPoints
            activeTool == Tool.LIQUIFY && liquifyPending.isNotEmpty() -> liquifyPending
            else -> return@Canvas
        }

        val path = Path().apply {
            moveTo(displayPath.first().x, displayPath.first().y)
            for (i in 1 until displayPath.size) lineTo(displayPath[i].x, displayPath[i].y)
        }
        drawPath(
            path = path,
            color = Color.Magenta.copy(alpha = 0.25f),
            style = Stroke(width = brushSize, cap = StrokeCap.Round, join = StrokeJoin.Round),
            blendMode = BlendMode.SrcOver
        )
    }
}
