package com.hereliesaz.graffitixr.feature.editor.rendering

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * A Composable that renders a bitmap distorted by a mesh of control points.
 * Allows the user to drag control points to warp the image.
 *
 * @param bitmap The source image to warp.
 * @param isEditable Whether the control points are visible and interactive.
 * @param gridRows Number of rows in the mesh grid.
 * @param gridCols Number of columns in the mesh grid.
 */
@Composable
fun WarpableImage(
    bitmap: ImageBitmap,
    modifier: Modifier = Modifier,
    isEditable: Boolean = false,
    gridRows: Int = 3,
    gridCols: Int = 3,
    meshState: List<Float>? = null,
    onMeshChanged: (List<Float>) -> Unit = {}
) {
    // Flattened array of [x, y, x, y, ...] coordinates for the mesh vertices
    // Size is (rows + 1) * (cols + 1) * 2
    
    // Initialize from state or generate new
    var verts by remember(bitmap, meshState) {
        mutableStateOf(
            if (meshState != null && meshState.isNotEmpty()) {
                meshState.toFloatArray()
            } else {
                generateInitialMesh(bitmap.width, bitmap.height, gridRows, gridCols)
            }
        )
    }

    var selectedPointIndex by remember { mutableStateOf(-1) }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isEditable) {
                    if (isEditable) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                selectedPointIndex = findNearestPoint(offset, verts)
                            },
                            onDragEnd = {
                                selectedPointIndex = -1
                                onMeshChanged(verts.toList())
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                if (selectedPointIndex != -1) {
                                    val idx = selectedPointIndex * 2
                                    // Update vertices
                                    val newVerts = verts.clone()
                                    newVerts[idx] += dragAmount.x
                                    newVerts[idx + 1] += dragAmount.y
                                    verts = newVerts
                                }
                            }
                        )
                    }
                }
        ) {
            drawIntoCanvas { canvas ->
                val paint = Paint().apply {
                    isAntiAlias = true
                    isFilterBitmap = true
                }
                
                // Draw distorted bitmap
                // Note: drawVertices is efficient but might show triangulation artifacts
                canvas.nativeCanvas.drawBitmapMesh(
                    bitmap.asAndroidBitmap(),
                    gridCols,
                    gridRows,
                    verts,
                    0,
                    null,
                    0,
                    paint
                )

                // Draw control points if editable
                if (isEditable) {
                    paint.color = android.graphics.Color.YELLOW
                    paint.strokeWidth = 5f
                    paint.style = Paint.Style.FILL

                    for (i in 0 until verts.size / 2) {
                        canvas.nativeCanvas.drawCircle(verts[i * 2], verts[i * 2 + 1], 20f, paint)
                    }
                    
                    // Draw grid lines
                    paint.color = android.graphics.Color.WHITE
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2f
                    // TODO: Draw lines connecting mesh points for better visualization
                }
            }
        }
    }
}

private fun generateInitialMesh(width: Int, height: Int, rows: Int, cols: Int): FloatArray {
    val verts = FloatArray((rows + 1) * (cols + 1) * 2)
    val w = width.toFloat()
    val h = height.toFloat()
    
    var index = 0
    for (y in 0..rows) {
        val fy = y.toFloat() / rows
        for (x in 0..cols) {
            val fx = x.toFloat() / cols
            verts[index++] = fx * w
            verts[index++] = fy * h
        }
    }
    return verts
}

private fun findNearestPoint(touch: Offset, verts: FloatArray, threshold: Float = 100f): Int {
    var nearestIdx = -1
    var minDist = Float.MAX_VALUE

    for (i in 0 until verts.size / 2) {
        val px = verts[i * 2]
        val py = verts[i * 2 + 1]
        val dist = (touch.x - px) * (touch.x - px) + (touch.y - py) * (touch.y - py)
        
        if (dist < minDist && dist < threshold * threshold) {
            minDist = dist
            nearestIdx = i
        }
    }
    return nearestIdx
}
