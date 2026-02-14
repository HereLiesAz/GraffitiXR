package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.zIndex

@Composable
fun UnwarpBackground(
    targetImage: Bitmap?,
    points: List<Offset>,
    activePointIndex: Int
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

    BoxWithConstraints(modifier = Modifier.fillMaxSize().zIndex(10f)) {
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
                .zIndex(10f)
                .pointerInput(renderWidth, renderHeight, renderOffsetX, renderOffsetY) {
                    detectDragGestures(
                        onDragStart = { touchPos ->
                            var closestIndex = -1
                            var minDst = Float.MAX_VALUE
                            val threshold = 100f

                            points.forEachIndexed { index, normalizedPoint ->
                                val px = renderOffsetX + normalizedPoint.x * renderWidth
                                val py = renderOffsetY + normalizedPoint.y * renderHeight
                                val dst = (touchPos - Offset(px, py)).getDistance()
                                if (dst < threshold && dst < minDst) {
                                    minDst = dst
                                    closestIndex = index
                                }
                            }
                            onPointIndexChanged(closestIndex)
                            if (closestIndex != -1) {
                                onMagnifierPositionChanged(touchPos)
                            }
                        },
                        onDragEnd = {
                            onPointIndexChanged(-1)
                        }
                    ) { change, _ ->
                        if (activePointIndex != -1) {
                            val touchPos = change.position
                            onMagnifierPositionChanged(touchPos)
                            val newX = ((touchPos.x - renderOffsetX) / renderWidth).coerceIn(0f, 1f)
                            val newY = ((touchPos.y - renderOffsetY) / renderHeight).coerceIn(0f, 1f)
                            points[activePointIndex] = Offset(newX, newY)
                        }
                    }
                }
        ) {
            if (activePointIndex != -1) {
                val point = points[activePointIndex]
                val pixelX = (point.x * targetImage.width).toInt().coerceIn(0, targetImage.width - 1)
                val pixelY = (point.y * targetImage.height).toInt().coerceIn(0, targetImage.height - 1)

                val zoomFactor = 4
                val magnifierSize = 300
                val srcSize = magnifierSize / zoomFactor

                val actualSrcWidth = srcSize.coerceAtMost(targetImage.width)
                val actualSrcHeight = srcSize.coerceAtMost(targetImage.height)

                val startX = (pixelX - actualSrcWidth / 2).coerceIn(0, targetImage.width - actualSrcWidth)
                val startY = (pixelY - actualSrcHeight / 2).coerceIn(0, targetImage.height - actualSrcHeight)

                if (actualSrcWidth > 0 && actualSrcHeight > 0) {
                    val screenWidthPx = with(density) { screenWidth.toPx() }
                    val magOffsetX = if (magnifierPosition.x < screenWidthPx / 2) {
                        (screenWidthPx - magnifierSize - 50).toInt()
                    } else {
                        50
                    }
                    val magOffsetY = 50

                    Box(
                        modifier = Modifier
                            .offset { IntOffset(magOffsetX, magOffsetY) }
                            .size(with(density) { magnifierSize.toDp() })
                            .clip(CircleShape)
                            .background(Color.White)
                            .padding(2.dp)
                            .clip(CircleShape)
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawImage(
                                image = targetImage.asImageBitmap(),
                                srcOffset = IntOffset(startX, startY),
                                srcSize = IntSize(actualSrcWidth, actualSrcHeight),
                                dstSize = IntSize(size.width.toInt(), size.height.toInt())
                            )
                            drawLine(Color.Cyan, Offset(size.width/2, 0f), Offset(size.width/2, size.height), strokeWidth = 2f)
                            drawLine(Color.Cyan, Offset(0f, size.height/2), Offset(size.width, size.height/2), strokeWidth = 2f)
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.medium)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Drag corners to match target surface",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isRightHanded) {
                    Button(onClick = { onConfirm(points) }) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Rectify & Use")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = onRetake) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retake", tint = Color.White)
                    }
                } else {
                    IconButton(onClick = onRetake) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retake", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Button(onClick = { onConfirm(points) }) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Rectify & Use")
                    }
                }
            }
        }
    }
}
