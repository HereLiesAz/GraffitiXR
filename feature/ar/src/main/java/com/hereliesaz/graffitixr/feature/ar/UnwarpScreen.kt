// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/UnwarpUi.kt
package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

@Composable
fun UnwarpBackground(
    targetImage: Bitmap?,
    points: List<Offset>,
    activePointIndex: Int,
    onPointIndexChanged: (Int) -> Unit,
    onMagnifierPositionChanged: (Offset) -> Unit
) {
    if (targetImage == null) return
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            bitmap = targetImage.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        Canvas(modifier = Modifier.fillMaxSize().onSizeChanged { canvasSize = it }) {
            if (points.size == 4) {
                val mappedPoints = points.map {
                    Offset(it.x * size.width, it.y * size.height)
                }

                val path = Path().apply {
                    moveTo(mappedPoints[0].x, mappedPoints[0].y)
                    lineTo(mappedPoints[1].x, mappedPoints[1].y)
                    lineTo(mappedPoints[2].x, mappedPoints[2].y)
                    lineTo(mappedPoints[3].x, mappedPoints[3].y)
                    close()
                }

                drawPath(
                    path = path,
                    color = Color.Cyan,
                    style = Stroke(width = 5f)
                )

                mappedPoints.forEachIndexed { index, offset ->
                    drawCircle(
                        color = if (index == activePointIndex) Color.Magenta else Color.Cyan,
                        radius = if (index == activePointIndex) 30f else 20f,
                        center = offset
                    )
                }
            }
        }
    }
}

@Composable
fun UnwarpUi(
    isRightHanded: Boolean,
    targetImage: Bitmap?,
    points: List<Offset>,
    activePointIndex: Int,
    magnifierPosition: Offset,
    onPointIndexChanged: (Int) -> Unit,
    onPointMoved: (Int, Offset) -> Unit,
    onMagnifierPositionChanged: (Offset) -> Unit,
    onConfirm: (List<Offset>) -> Unit,
    onRetake: () -> Unit
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { startOffset ->
                        val normalizedStart = Offset(startOffset.x / size.width, startOffset.y / size.height)
                        val closestIndex = points.indices.minByOrNull {
                            (points[it] - normalizedStart).getDistance()
                        } ?: -1

                        if (closestIndex != -1) {
                            onPointIndexChanged(closestIndex)
                            onMagnifierPositionChanged(startOffset)
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (activePointIndex in points.indices) {
                            val normalizedDrag = Offset(dragAmount.x / size.width, dragAmount.y / size.height)
                            val currentPos = points[activePointIndex]
                            val newPos = Offset(
                                x = (currentPos.x + normalizedDrag.x).coerceIn(0f, 1f),
                                y = (currentPos.y + normalizedDrag.y).coerceIn(0f, 1f)
                            )
                            onPointMoved(activePointIndex, newPos)
                            onMagnifierPositionChanged(change.position)
                        }
                    },
                    onDragEnd = {
                        onPointIndexChanged(-1)
                    }
                )
            }
            .onSizeChanged { canvasSize = it }
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FloatingActionButton(
                onClick = onRetake,
                containerColor = MaterialTheme.colorScheme.errorContainer
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Retake")
            }

            FloatingActionButton(
                onClick = {
                    if (targetImage != null) {
                        val imageAspect = targetImage.width.toFloat() / targetImage.height.toFloat()
                        val screenAspect = canvasSize.width.toFloat() / canvasSize.height.toFloat()

                        var renderWidth = canvasSize.width.toFloat()
                        var renderHeight = canvasSize.height.toFloat()
                        var offsetX = 0f
                        var offsetY = 0f

                        if (imageAspect > screenAspect) {
                            renderHeight = renderWidth / imageAspect
                            offsetY = (canvasSize.height - renderHeight) / 2f
                        } else {
                            renderWidth = renderHeight * imageAspect
                            offsetX = (canvasSize.width - renderWidth) / 2f
                        }

                        val scaleX = targetImage.width / renderWidth
                        val scaleY = targetImage.height / renderHeight

                        val actualBitmapPoints = points.map { pt ->
                            val screenX = pt.x * canvasSize.width
                            val screenY = pt.y * canvasSize.height

                            Offset(
                                ((screenX - offsetX) * scaleX).coerceIn(0f, targetImage.width.toFloat()),
                                ((screenY - offsetY) * scaleY).coerceIn(0f, targetImage.height.toFloat())
                            )
                        }
                        onConfirm(actualBitmapPoints)
                    }
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(Icons.Default.Check, contentDescription = "Confirm")
            }
        }
    }
}