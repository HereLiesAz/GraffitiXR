package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun UnwarpBackground(
    targetImage: Bitmap?,
    points: List<Offset>,
    activePointIndex: Int,
    onPointIndexChanged: (Int) -> Unit,
    onMagnifierPositionChanged: (Offset) -> Unit
) {
    if (targetImage == null) return

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        val density = LocalDensity.current

        val imageAspectRatio = targetImage.width.toFloat() / targetImage.height.toFloat()
        val screenAspectRatio = with(density) { screenWidth.toPx() / screenHeight.toPx() }

        val renderWidth: Float
        val renderHeight: Float
        val renderOffsetX: Float
        val renderOffsetY: Float

        if (imageAspectRatio > screenAspectRatio) {
            renderWidth = with(density) { screenWidth.toPx() }
            renderHeight = renderWidth / imageAspectRatio
            renderOffsetX = 0f
            renderOffsetY = (with(density) { screenHeight.toPx() } - renderHeight) / 2f
        } else {
            renderHeight = with(density) { screenHeight.toPx() }
            renderWidth = renderHeight * imageAspectRatio
            renderOffsetY = 0f
            renderOffsetX = (with(density) { screenWidth.toPx() } - renderWidth) / 2f
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawImage(
                image = targetImage.asImageBitmap(),
                dstOffset = IntOffset(renderOffsetX.toInt(), renderOffsetY.toInt()),
                dstSize = IntSize(renderWidth.toInt(), renderHeight.toInt())
            )

            // Draw connecting lines (Quadrilateral)
            if (points.size == 4) {
                val p0 = Offset(renderOffsetX + points[0].x * renderWidth, renderOffsetY + points[0].y * renderHeight)
                val p1 = Offset(renderOffsetX + points[1].x * renderWidth, renderOffsetY + points[1].y * renderHeight)
                val p2 = Offset(renderOffsetX + points[2].x * renderWidth, renderOffsetY + points[2].y * renderHeight)
                val p3 = Offset(renderOffsetX + points[3].x * renderWidth, renderOffsetY + points[3].y * renderHeight)

                drawLine(Color.Cyan, p0, p1, strokeWidth = 5f)
                drawLine(Color.Cyan, p1, p2, strokeWidth = 5f)
                drawLine(Color.Cyan, p2, p3, strokeWidth = 5f)
                drawLine(Color.Cyan, p3, p0, strokeWidth = 5f)
            }

            // Draw Corner Points
            points.forEachIndexed { index, normalizedPoint ->
                val px = renderOffsetX + normalizedPoint.x * renderWidth
                val py = renderOffsetY + normalizedPoint.y * renderHeight

                drawCircle(
                    color = if (index == activePointIndex) Color.Yellow else Color.Red,
                    radius = 20f,
                    center = Offset(px, py)
                )
            }
        }
    }
}

@Composable
fun UnwarpUi(
    isRightHanded: Boolean,
    targetImage: Bitmap?,
    points: MutableList<Offset>,
    activePointIndex: Int,
    magnifierPosition: Offset,
    onPointIndexChanged: (Int) -> Unit,
    onMagnifierPositionChanged: (Offset) -> Unit,
    onConfirm: (List<Offset>) -> Unit,
    onRetake: () -> Unit
) {
    if (targetImage == null) return

    val density = LocalDensity.current

    // CRITICAL FIX: Use rememberUpdatedState so the gesture lambda always calls the latest function instance
    val currentOnPointIndexChanged by rememberUpdatedState(onPointIndexChanged)
    val currentOnMagnifierPositionChanged by rememberUpdatedState(onMagnifierPositionChanged)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight

        val imageAspectRatio = targetImage.width.toFloat() / targetImage.height.toFloat()
        val screenAspectRatio = with(density) { screenWidth.toPx() / screenHeight.toPx() }

        val renderWidth: Float
        val renderHeight: Float
        val renderOffsetX: Float
        val renderOffsetY: Float

        if (imageAspectRatio > screenAspectRatio) {
            renderWidth = with(density) { screenWidth.toPx() }
            renderHeight = renderWidth / imageAspectRatio
            renderOffsetX = 0f
            renderOffsetY = (with(density) { screenHeight.toPx() } - renderHeight) / 2f
        } else {
            renderHeight = with(density) { screenHeight.toPx() }
            renderWidth = renderHeight * imageAspectRatio
            renderOffsetY = 0f
            renderOffsetX = (with(density) { screenWidth.toPx() } - renderWidth) / 2f
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(renderWidth, renderHeight, renderOffsetX, renderOffsetY) {
                    // FIX: Local variable strictly for this gesture session.
                    // This persists between onDragStart and onDrag, solving the "stale state" bug.
                    var draggedPointIndex = -1

                    detectDragGestures(
                        onDragStart = { touchPos ->
                            var closestIndex = -1
                            var minDst = Float.MAX_VALUE
                            val threshold = 100f // Touch slop/radius

                            points.forEachIndexed { index, normalizedPoint ->
                                val px = renderOffsetX + normalizedPoint.x * renderWidth
                                val py = renderOffsetY + normalizedPoint.y * renderHeight
                                val dst = (touchPos - Offset(px, py)).getDistance()

                                if (dst < minDst && dst < threshold) {
                                    minDst = dst
                                    closestIndex = index
                                }
                            }

                            draggedPointIndex = closestIndex

                            // Notify parent to highlight the point (UI feedback)
                            if (draggedPointIndex != -1) {
                                currentOnPointIndexChanged(draggedPointIndex)
                            }
                        },
                        onDrag = { change, dragAmount ->
                            if (draggedPointIndex != -1) {
                                change.consume()

                                val currentPoint = points[draggedPointIndex]

                                // Convert drag pixels to normalized UV space (0..1)
                                val dx = dragAmount.x / renderWidth
                                val dy = dragAmount.y / renderHeight

                                val newX = (currentPoint.x + dx).coerceIn(0f, 1f)
                                val newY = (currentPoint.y + dy).coerceIn(0f, 1f)

                                // Update the mutable list state (triggers recomposition)
                                points[draggedPointIndex] = Offset(newX, newY)

                                // Calculate magnifier position (Screen space)
                                val magX = renderOffsetX + newX * renderWidth
                                val magY = renderOffsetY + newY * renderHeight
                                currentOnMagnifierPositionChanged(Offset(magX, magY))
                            }
                        },
                        onDragEnd = {
                            draggedPointIndex = -1
                            currentOnPointIndexChanged(-1)
                        },
                        onDragCancel = {
                            draggedPointIndex = -1
                            currentOnPointIndexChanged(-1)
                        }
                    )
                }
        ) {
            // Overlay controls (Magnifier could go here)
        }
    }
}