package com.hereliesaz.graffitixr

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.rememberAsyncImagePainter
import coil.imageLoader
import coil.request.ImageRequest

/**
 * An editor for placing and transforming an overlay image on a static background image.
 * This composable provides a four-point perspective transformation UI with draggable corners
 * and two-finger gestures for scaling and rotation.
 */
@Composable
fun StaticImageEditor(uiState: UiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    var overlayBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var draggedCornerIndex by remember { mutableStateOf<Int?>(null) }

    // Load the overlay image as a bitmap when the URI changes
    LaunchedEffect(uiState.imageUri) {
        overlayBitmap = null // Reset on new image
        uiState.imageUri?.let { uri ->
            val request = ImageRequest.Builder(context)
                .data(uri)
                .allowHardware(false) // Required for software rendering on canvas
                .target { result ->
                    overlayBitmap = (result as android.graphics.drawable.BitmapDrawable).bitmap
                }
                .build()
            context.imageLoader.enqueue(request)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Display the background image
        uiState.backgroundImageUri?.let {
            Image(
                painter = rememberAsyncImagePainter(it),
                contentDescription = "Background for Mock-up",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }

        // Canvas for drawing the overlay and handles
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { // Gesture detector for scale/rotation
                    detectTransformGestures { _, _, zoom, rotation ->
                        if (uiState.stickerCorners.size == 4) {
                            val currentCorners = uiState.stickerCorners
                            val centerX = currentCorners.map { it.x }.average().toFloat()
                            val centerY = currentCorners.map { it.y }.average().toFloat()
                            val center = Offset(centerX, centerY)

                            val newCorners = currentCorners.map { corner ->
                                // Create a vector from the center to the corner
                                val vector = corner - center
                                // Scale the vector
                                val scaledVector = vector * zoom
                                // Rotate the scaled vector
                                val rotatedVector = scaledVector.rotateBy(rotation)
                                // Get the new corner position
                                center + rotatedVector
                            }
                            viewModel.onStickerCornersChange(newCorners)
                        }
                    }
                }
                .pointerInput(uiState.stickerCorners) { // Gesture detector for dragging corners
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            // Determine which corner is being dragged based on proximity
                            draggedCornerIndex = uiState.stickerCorners
                                .map { corner -> (corner - startOffset).getDistance() }
                                .withIndex()
                                .minByOrNull { it.value }
                                ?.takeIf { it.value < 60f } // 60px touch radius
                                ?.index
                        },
                        onDragEnd = {
                            draggedCornerIndex = null
                        }
                    ) { change, dragAmount ->
                        draggedCornerIndex?.let { index ->
                            val newCorners = uiState.stickerCorners.toMutableList()
                            newCorners[index] = newCorners[index] + dragAmount
                            viewModel.onStickerCornersChange(newCorners)
                            change.consume()
                        }
                    }
                }
        ) {
            overlayBitmap?.let { bmp ->
                if (uiState.stickerCorners.size == 4) {
                    val matrix = Matrix()
                    // Define the source points as the corners of the bitmap
                    val src = floatArrayOf(
                        0f, 0f,                         // Top-left
                        bmp.width.toFloat(), 0f,        // Top-right
                        bmp.width.toFloat(), bmp.height.toFloat(), // Bottom-right
                        0f, bmp.height.toFloat()        // Bottom-left
                    )
                    // Define the destination points as the user-dragged corners
                    val dst = floatArrayOf(
                        uiState.stickerCorners[0].x, uiState.stickerCorners[0].y,
                        uiState.stickerCorners[1].x, uiState.stickerCorners[1].y,
                        uiState.stickerCorners[2].x, uiState.stickerCorners[2].y,
                        uiState.stickerCorners[3].x, uiState.stickerCorners[3].y
                    )

                    // Create the perspective transformation matrix
                    matrix.setPolyToPoly(src, 0, dst, 0, 4)

                    // Draw the bitmap with the transformation
                    drawIntoCanvas {
                        it.nativeCanvas.drawBitmap(bmp, matrix, null)
                    }
                }
            }

            // Draw draggable handles at each corner
            uiState.stickerCorners.forEach { corner ->
                drawCircle(
                    color = Color.Red.copy(alpha = 0.7f),
                    radius = 30f,
                    center = corner
                )
                drawCircle(
                    color = Color.White,
                    radius = 15f,
                    center = corner
                )
            }
        }
    }
}

/**
 * Helper extension function to rotate an Offset by a given angle in degrees.
 */
private fun Offset.rotateBy(degrees: Float): Offset {
    val angleRad = Math.toRadians(degrees.toDouble())
    val cos = kotlin.math.cos(angleRad).toFloat()
    val sin = kotlin.math.sin(angleRad).toFloat()
    return Offset(x * cos - y * sin, x * sin + y * cos)
}