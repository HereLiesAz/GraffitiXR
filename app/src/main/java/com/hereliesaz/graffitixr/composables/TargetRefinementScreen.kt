package com.hereliesaz.graffitixr.composables

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.data.RefinementPath

@Composable
fun TargetRefinementScreen(
    targetImage: Bitmap?,
    keypoints: List<Offset>,
    paths: List<RefinementPath>,
    isEraser: Boolean,
    onPathAdded: (RefinementPath) -> Unit,
    onModeChanged: (Boolean) -> Unit,
    onConfirm: () -> Unit
) {
    if (targetImage == null) return

    var currentPath by remember { mutableStateOf<List<Offset>?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Image and Drawing Area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 80.dp) // Space for bottom bar
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            // Normalize coordinates
                            val normalizedStart = Offset(
                                offset.x / size.width.toFloat(),
                                offset.y / size.height.toFloat()
                            )
                            currentPath = listOf(normalizedStart)
                        },
                        onDrag = { change, _ ->
                            val normalizedPoint = Offset(
                                change.position.x / size.width.toFloat(),
                                change.position.y / size.height.toFloat()
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
            Canvas(modifier = Modifier.fillMaxSize()) {
                // 1. Draw Target Image
                drawImage(
                    image = targetImage.asImageBitmap(),
                    dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())
                )

                // 2. Draw Existing Mask Paths
                paths.forEach { rPath ->
                    val path = Path()
                    if (rPath.points.isNotEmpty()) {
                        path.moveTo(rPath.points.first().x * size.width, rPath.points.first().y * size.height)
                        for (i in 1 until rPath.points.size) {
                            path.lineTo(rPath.points[i].x * size.width, rPath.points[i].y * size.height)
                        }
                    }

                    // "Eraser" (Add) visually removes the red mask, so we don't draw it or draw clear?
                    // To simplify visualization: Red = Masked out (Subtract). Clear = Active.
                    // If we are painting a mask, we draw red.
                    // If we are erasing a mask, we ideally clear the red pixels.
                    // However, standard canvas doesn't support layer clearing easily without offscreen buffers.
                    // A simple visual approximation: Draw Red for Mask, Draw Transparent/White for Eraser (on top).

                    val color = if (rPath.isEraser) Color.Green.copy(alpha = 0.3f) else Color.Red.copy(alpha = 0.5f)

                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(
                            width = 50f,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                // 3. Draw Current Stroke
                currentPath?.let { points ->
                    val path = Path()
                    if (points.isNotEmpty()) {
                        path.moveTo(points.first().x * size.width, points.first().y * size.height)
                        for (i in 1 until points.size) {
                            path.lineTo(points[i].x * size.width, points[i].y * size.height)
                        }
                    }
                    val color = if (isEraser) Color.Green.copy(alpha = 0.3f) else Color.Red.copy(alpha = 0.5f)
                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(
                            width = 50f,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                // 4. Draw Keypoints
                keypoints.forEach { normalizedOffset ->
                    drawCircle(
                        color = Color.Green,
                        radius = 5f,
                        center = Offset(
                            normalizedOffset.x * size.width,
                            normalizedOffset.y * size.height
                        )
                    )
                }
            }
        }

        // Top Instruction
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.7f), MaterialTheme.shapes.medium)
                .padding(8.dp)
        ) {
            Text(
                text = "Refine Target: Paint Red to IGNORE areas.",
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
                // Tool Toggle
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    IconButton(
                        onClick = { onModeChanged(false) }, // Mask (Subtract)
                        modifier = Modifier
                            .size(48.dp)
                            .background(if (!isEraser) Color.Red else Color.Gray, CircleShape)
                            .border(2.dp, Color.White, CircleShape)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Mask", tint = Color.White)
                    }

                    IconButton(
                        onClick = { onModeChanged(true) }, // Unmask (Add)
                        modifier = Modifier
                            .size(48.dp)
                            .background(if (isEraser) Color.Green else Color.Gray, CircleShape)
                            .border(2.dp, Color.White, CircleShape)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Unmask", tint = Color.White)
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