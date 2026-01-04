package com.hereliesaz.graffitixr.composables

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun UnwarpScreen(
    targetImage: Bitmap?,
    onConfirm: (List<Offset>) -> Unit,
    onRetake: () -> Unit
) {
    if (targetImage == null) return

    // Points normalized (0..1)
    val points = remember { mutableStateListOf<Offset>() }
    // Initialize points to corners (inset slightly) if empty
    LaunchedEffect(targetImage) {
        if (points.isEmpty()) {
            points.add(Offset(0.2f, 0.2f)) // TL
            points.add(Offset(0.8f, 0.2f)) // TR
            points.add(Offset(0.8f, 0.8f)) // BR
            points.add(Offset(0.2f, 0.8f)) // BL
        }
    }

    var activePointIndex by remember { mutableStateOf(-1) }
    var magnifierPosition by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        val density = LocalDensity.current

        // Calculate image scale and offset to fit center
        val imageAspectRatio = targetImage.width.toFloat() / targetImage.height.toFloat()
        val screenAspectRatio = with(density) { screenWidth.toPx() / screenHeight.toPx() }

        val renderWidth: Float
        val renderHeight: Float
        val renderOffsetX: Float
        val renderOffsetY: Float

        if (imageAspectRatio > screenAspectRatio) {
            // Fit Width
            renderWidth = with(density) { screenWidth.toPx() }
            renderHeight = renderWidth / imageAspectRatio
            renderOffsetX = 0f
            renderOffsetY = (with(density) { screenHeight.toPx() } - renderHeight) / 2f
        } else {
            // Fit Height
            renderHeight = with(density) { screenHeight.toPx() }
            renderWidth = renderHeight * imageAspectRatio
            renderOffsetY = 0f
            renderOffsetX = (with(density) { screenWidth.toPx() } - renderWidth) / 2f
        }

        // Draw Image and Overlay
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(renderWidth, renderHeight, renderOffsetX, renderOffsetY) {
                    detectDragGestures(
                        onDragStart = { touchPos ->
                            // Find closest point
                            var closestIndex = -1
                            var minDst = Float.MAX_VALUE
                            val threshold = 100f // Touch radius

                            points.forEachIndexed { index, normalizedPoint ->
                                val px = renderOffsetX + normalizedPoint.x * renderWidth
                                val py = renderOffsetY + normalizedPoint.y * renderHeight
                                val dst = (touchPos - Offset(px, py)).getDistance()
                                if (dst < threshold && dst < minDst) {
                                    minDst = dst
                                    closestIndex = index
                                }
                            }
                            activePointIndex = closestIndex
                            if (activePointIndex != -1) {
                                magnifierPosition = touchPos
                            }
                        },
                        onDrag = { change, _ ->
                            if (activePointIndex != -1) {
                                val touchPos = change.position
                                magnifierPosition = touchPos
                                val newX = ((touchPos.x - renderOffsetX) / renderWidth).coerceIn(0f, 1f)
                                val newY = ((touchPos.y - renderOffsetY) / renderHeight).coerceIn(0f, 1f)
                                points[activePointIndex] = Offset(newX, newY)
                            }
                        },
                        onDragEnd = {
                            activePointIndex = -1
                        }
                    )
                }
        ) {
            // Draw Image
            drawImage(
                image = targetImage.asImageBitmap(),
                dstOffset = IntOffset(renderOffsetX.toInt(), renderOffsetY.toInt()),
                dstSize = IntSize(renderWidth.toInt(), renderHeight.toInt())
            )

            // Draw Connection Lines
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

            // Draw Points
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

        // Magnifier (Zoom Loop)
        if (activePointIndex != -1) {
            val point = points[activePointIndex]
            val pixelX = (point.x * targetImage.width).toInt().coerceIn(0, targetImage.width - 1)
            val pixelY = (point.y * targetImage.height).toInt().coerceIn(0, targetImage.height - 1)

            // Calculate Crop Region (centered on point)
            val zoomFactor = 4
            val magnifierSize = 300 // px
            val srcSize = magnifierSize / zoomFactor
            val startX = (pixelX - srcSize / 2).coerceIn(0, targetImage.width - srcSize)
            val startY = (pixelY - srcSize / 2).coerceIn(0, targetImage.height - srcSize)

            // We render the crop manually or use a library. Simple way: Sub-bitmap
            // To be safe against crashes, ensure dimensions > 0
            if (srcSize > 0) {
                // Determine placement of magnifier (avoid being under finger)
                // Place it opposite to the touch
                val magOffsetX = if (magnifierPosition.x < with(density) { screenWidth.toPx() } / 2) {
                    with(density) { (screenWidth.toPx() - magnifierSize - 50).toInt() }
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
                        .padding(2.dp) // Border
                        .clip(CircleShape)
                ) {
                    // Create crop on the fly (performance warning: usually better to use BitmapRegionDecoder or just Canvas scaling)
                    // For Compose, we can use BitmapPainter with srcOffset/srcSize
                    // But creating a small bitmap is easier for now given existing tools
                     // Safe handling of bitmap creation without try-catch around composable
                     val crop = try {
                         Bitmap.createBitmap(targetImage, startX, startY, srcSize.coerceAtMost(targetImage.width - startX), srcSize.coerceAtMost(targetImage.height - startY))
                     } catch (e: Exception) {
                         null
                     }
                     if (crop != null) {
                         Image(
                             bitmap = crop.asImageBitmap(),
                             contentDescription = "Zoom",
                             modifier = Modifier.fillMaxSize(),
                             contentScale = ContentScale.FillBounds
                         )
                         // Crosshair
                         Canvas(modifier = Modifier.fillMaxSize()) {
                             drawLine(Color.Cyan, Offset(size.width/2, 0f), Offset(size.width/2, size.height), strokeWidth = 2f)
                             drawLine(Color.Cyan, Offset(0f, size.height/2), Offset(size.width, size.height/2), strokeWidth = 2f)
                         }
                     }
                }
            }
        }

        // Instruction Text
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 32.dp)
                .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.medium)
                .padding(8.dp)
        ) {
            Text(
                text = "Drag corners to match target surface",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge
            )
        }

        // Controls
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
