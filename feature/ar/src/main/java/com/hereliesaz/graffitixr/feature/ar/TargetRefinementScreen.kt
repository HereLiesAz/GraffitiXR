package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.data.RefinementPath

@Composable
fun TargetRefinementScreen(
    targetImage: Bitmap?,
    mask: Bitmap?,
    keypoints: List<Offset>,
    paths: List<RefinementPath>,
    isEraser: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onPathAdded: (RefinementPath) -> Unit,
    onModeChanged: (Boolean) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onConfirm: () -> Unit
) {
    if (targetImage == null) return

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var currentPath by remember { mutableStateOf<List<Offset>?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 10f)
                    val rad = 0f
                    val cos = kotlin.math.cos(rad)
                    val sin = kotlin.math.sin(rad)
                    val newPanX = pan.x * cos - pan.y * sin
                    val newPanY = pan.x * sin + pan.y * cos
                    val rotatedPan = Offset(newPanX, newPanY)
                    offset += rotatedPan
                }
            }
    ) {
        // Drawing Canvas
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .semantics {
                    contentDescription = "Image Refinement Canvas. Use gestures to pan and zoom. Select tools below to edit mask."
                }
                .pointerInput(scale, offset) {
                    detectDragGestures(
                        onDragStart = { touchOffset ->
                            val imageWidth = size.width
                            val imageHeight = size.height

                            val imageX = (touchOffset.x - offset.x) / scale
                            val imageY = (touchOffset.y - offset.y) / scale

                            if (imageX in 0f..imageWidth.toFloat() && imageY in 0f..imageHeight.toFloat()) {
                                val normalizedStart = Offset(
                                    imageX / imageWidth.toFloat(),
                                    imageY / imageHeight.toFloat()
                                )
                                currentPath = listOf(normalizedStart)
                            }
                        },
                        onDrag = { change, _ ->
                            val touchOffset = change.position
                            val imageWidth = size.width
                            val imageHeight = size.height

                            val imageX = (touchOffset.x - offset.x) / scale
                            val imageY = (touchOffset.y - offset.y) / scale

                            val normalizedPoint = Offset(
                                imageX / imageWidth.toFloat(),
                                imageY / imageHeight.toFloat()
                            )
                            currentPath = currentPath?.plus(normalizedPoint)
                        },
                        onDragEnd = {
                            currentPath?.let {
                                onPathAdded(RefinementPath(it, isEraser))
                            }
                            currentPath = null
                        }
                    )
                }
        ) {
            val imageWidth = size.width
            val imageHeight = size.height
            val imageSize = androidx.compose.ui.unit.IntSize(imageWidth.toInt(), imageHeight.toInt())

            withTransform({
                translate(left = offset.x, top = offset.y)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                val imageBitmap = targetImage.asImageBitmap()

                // 1. Draw Dimmed Background (Shows what is excluded)
                drawImage(
                    image = imageBitmap,
                    dstSize = imageSize,
                    alpha = 0.3f
                )

                // 2. Bright Foreground with Masking
                // Create a layer to apply the compound mask (Auto + Paths)
                drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, imageWidth, imageHeight), Paint())

                // A. Base Image (Full Bright)
                drawImage(
                    image = imageBitmap,
                    dstSize = imageSize
                )

                // B. Apply Auto Mask (DstIn) -> Only keeps parts where AutoMask is opaque
                if (mask != null) {
                    drawImage(
                        image = mask.asImageBitmap(),
                        dstSize = imageSize,
                        blendMode = BlendMode.DstIn
                    )
                }

                // C. Restore Paths (Eraser) -> Adds back from Base Image
                // We use BitmapShader to draw the image pixels along the stroke.
                val activeEraserPaths = paths.filter { it.isEraser } +
                    (if (isEraser && currentPath != null) listOf(RefinementPath(currentPath!!, true)) else emptyList())

                if (activeEraserPaths.isNotEmpty()) {
                    val paint = android.graphics.Paint().apply {
                        shader = android.graphics.BitmapShader(targetImage, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 50f
                        strokeCap = android.graphics.Paint.Cap.ROUND
                        strokeJoin = android.graphics.Paint.Join.ROUND
                    }

                    val matrix = android.graphics.Matrix()
                    val scaleX = imageWidth / targetImage.width
                    val scaleY = imageHeight / targetImage.height
                    matrix.setScale(scaleX, scaleY)
                    paint.shader.setLocalMatrix(matrix)

                    activeEraserPaths.forEach { rPath ->
                        val path = Path()
                        if (rPath.points.isNotEmpty()) {
                            val start = rPath.points.first()
                            path.moveTo(start.x * imageWidth, start.y * imageHeight)

                            if (rPath.points.size == 1) {
                                // Draw a tiny line to ensure single taps render with Round Cap
                                path.lineTo(start.x * imageWidth + 0.1f, start.y * imageHeight)
                            } else {
                                for (i in 1 until rPath.points.size) {
                                    path.lineTo(rPath.points[i].x * imageWidth, rPath.points[i].y * imageHeight)
                                }
                            }
                        }

                        drawContext.canvas.nativeCanvas.drawPath(path.asAndroidPath(), paint)

                        // Green hint
                        drawPath(path, Color.Green.copy(alpha = 0.2f), style = Stroke(width = 50f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }

                // D. Remove Paths (Mask) -> Removes from current result
                // We use Clear blend mode to punch holes in the layer.
                val activeMaskPaths = paths.filter { !it.isEraser } +
                    (if (!isEraser && currentPath != null) listOf(RefinementPath(currentPath!!, false)) else emptyList())

                activeMaskPaths.forEach { rPath ->
                    val path = Path()
                    if (rPath.points.isNotEmpty()) {
                        val start = rPath.points.first()
                        path.moveTo(start.x * imageWidth, start.y * imageHeight)

                        if (rPath.points.size == 1) {
                            path.lineTo(start.x * imageWidth + 0.1f, start.y * imageHeight)
                        } else {
                            for (i in 1 until rPath.points.size) {
                                path.lineTo(rPath.points[i].x * imageWidth, rPath.points[i].y * imageHeight)
                            }
                        }
                    }

                    drawPath(
                        path = path,
                        color = Color.Transparent,
                        style = Stroke(width = 50f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        blendMode = BlendMode.Clear
                    )
                }

                drawContext.canvas.restore() // End Mask Layer

                // 3. Draw Indicators
                // Red indicators for Masked paths (optional)
                activeMaskPaths.forEach { rPath ->
                     val path = Path()
                     if (rPath.points.isNotEmpty()) {
                        val start = rPath.points.first()
                        path.moveTo(start.x * imageWidth, start.y * imageHeight)

                        if (rPath.points.size == 1) {
                            path.lineTo(start.x * imageWidth + 0.1f, start.y * imageHeight)
                        } else {
                            for (i in 1 until rPath.points.size) {
                                path.lineTo(rPath.points[i].x * imageWidth, rPath.points[i].y * imageHeight)
                            }
                        }
                     }
                     drawPath(path, Color.Red.copy(alpha = 0.2f), style = Stroke(width = 50f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }

                // Draw Keypoints
                keypoints.forEach { normalizedOffset ->
                    drawCircle(
                        color = Color.Yellow,
                        radius = 8f / scale,
                        center = Offset(
                            normalizedOffset.x * imageWidth,
                            normalizedOffset.y * imageHeight
                        )
                    )
                }
            }
        }

        // Instructions
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.7f), MaterialTheme.shapes.medium)
                .padding(8.dp)
        ) {
            Text(
                text = if (isEraser) "ADD (+): Restore areas (Green)" else "REMOVE (-): Mask out areas",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Bottom Controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.9f))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tools Row: [Undo] [-] [+] [Redo]
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Undo
                    IconButton(onClick = onUndo, enabled = canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", tint = if(canUndo) Color.White else Color.Gray)
                    }

                    // Remove (Mask / Red)
                    IconButton(
                        onClick = { onModeChanged(false) },
                        modifier = Modifier
                            .size(48.dp)
                            .background(if (!isEraser) Color.Red else Color.DarkGray, CircleShape)
                            .border(2.dp, Color.White, CircleShape)
                            .semantics {
                                stateDescription = if (!isEraser) "Selected" else "Not selected"
                            }
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Remove (Mask)", tint = Color.White)
                    }

                    // Add (Unmask / Green)
                    IconButton(
                        onClick = { onModeChanged(true) },
                        modifier = Modifier
                            .size(48.dp)
                            .background(if (isEraser) Color.Green else Color.DarkGray, CircleShape)
                            .border(2.dp, Color.White, CircleShape)
                            .semantics {
                                stateDescription = if (isEraser) "Selected" else "Not selected"
                            }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add (Restore)", tint = Color.White)
                    }

                    // Redo
                    IconButton(onClick = onRedo, enabled = canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo", tint = if(canRedo) Color.White else Color.Gray)
                    }
                }

                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Confirm")
                }
            }
        }
    }
}
