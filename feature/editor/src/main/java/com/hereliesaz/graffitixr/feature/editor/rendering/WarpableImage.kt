package com.hereliesaz.graffitixr.feature.editor.rendering

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput

@Composable
fun WarpableImage(
    bitmap: ImageBitmap,
    isEditable: Boolean,
    meshState: List<Float>?,
    onMeshChanged: (List<Float>) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(isEditable) {
                if (isEditable) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()

                        // Calculate new mesh positions based on drag gesture
                        val currentMesh = meshState ?: generateDefaultMesh()
                        val newMesh = calculateNewMeshPositions(
                            currentMesh = currentMesh,
                            dragPosition = change.position,
                            dragAmount = dragAmount
                        )

                        // Fire callback upwards
                        onMeshChanged(newMesh)
                    }
                }
            }
    ) {
        drawIntoCanvas { canvas ->
            // In a real implementation, map this FloatArray via Android's Canvas
            // drawBitmapMesh to render the distortion.
            // canvas.nativeCanvas.drawBitmapMesh(...)
        }
    }
}

private fun generateDefaultMesh(): List<Float> {
    // Generate a default flat mesh grid if none exists
    return List(64) { 0f }
}

/**
 * Utility function to translate dragging into vertex displacement.
 */
private fun calculateNewMeshPositions(
    currentMesh: List<Float>,
    dragPosition: Offset,
    dragAmount: Offset
): List<Float> {
    val updatedMesh = currentMesh.toMutableList()
    val influenceRadius = 150f

    for (i in 0 until updatedMesh.size - 1 step 2) {
        val vx = updatedMesh[i]
        val vy = updatedMesh[i + 1]

        val dx = vx - dragPosition.x
        val dy = vy - dragPosition.y
        val distanceSq = (dx * dx) + (dy * dy)

        if (distanceSq < (influenceRadius * influenceRadius)) {
            val falloff = 1f - (Math.sqrt(distanceSq.toDouble()).toFloat() / influenceRadius)
            updatedMesh[i] += dragAmount.x * falloff
            updatedMesh[i + 1] += dragAmount.y * falloff
        }
    }

    return updatedMesh
}