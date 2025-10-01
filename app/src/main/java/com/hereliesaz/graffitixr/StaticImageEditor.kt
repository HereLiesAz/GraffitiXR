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
 * A composable editor for placing and transforming an overlay image onto a static background.
 *
 * This screen is the core of the "Mock-up Mode". It displays a background image and allows the
 * user to apply an overlay image on top. The overlay can be manipulated using two primary gestures:
 * 1.  **Four-Corner Dragging:** Red handles at each corner of the overlay can be dragged to
 *     apply a perspective transformation.
 * 2.  **Two-Finger Gestures:** Pinch-to-zoom and twist-to-rotate gestures can be used to scale
 *     and rotate the overlay image around its center.
 *
 * The overlay image is loaded asynchronously using Coil and drawn onto a `Canvas` using a
 * calculated perspective transformation matrix.
 *
 * @param uiState The current [UiState] of the application. This composable uses the
 *   `imageUri`, `backgroundImageUri`, and `stickerCorners` from the state.
 * @param viewModel The [MainViewModel] instance, used to notify of changes to the
 *   `stickerCorners` when the user manipulates the overlay.
 */
@Composable
fun StaticImageEditor(uiState: UiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    var overlayBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var draggedCornerIndex by remember { mutableStateOf<Int?>(null) }

    // A LaunchedEffect that loads the overlay image URI into a Bitmap.
    // This is necessary because the Canvas requires a Bitmap object to perform the
    // matrix transformation. Hardware bitmaps are disallowed to enable software rendering.
    LaunchedEffect(uiState.imageUri) {
        overlayBitmap = null // Reset bitmap when a new image is selected
        uiState.imageUri?.let { uri ->
            val request = ImageRequest.Builder(context)
                .data(uri)
                .allowHardware(false) // Required for software rendering on a Canvas
                .target { result ->
                    overlayBitmap = (result as android.graphics.drawable.BitmapDrawable).bitmap
                }
                .build()
            context.imageLoader.enqueue(request)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Display the user-selected background image.
        uiState.backgroundImageUri?.let {
            Image(
                painter = rememberAsyncImagePainter(it),
                contentDescription = "Background for Mock-up",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }

        // The main canvas for drawing the transformed overlay and the draggable handles.
        // It combines two pointerInput modifiers to handle different gestures.
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { // Gesture detector for two-finger scale and rotation.
                    detectTransformGestures { _, _, zoom, rotation ->
                        if (uiState.stickerCorners.size == 4) {
                            val currentCorners = uiState.stickerCorners
                            val centerX = currentCorners.map { it.x }.average().toFloat()
                            val centerY = currentCorners.map { it.y }.average().toFloat()
                            val center = Offset(centerX, centerY)

                            val newCorners = currentCorners.map { corner ->
                                // Scale and rotate each corner's vector relative to the center.
                                val vector = corner - center
                                val scaledVector = vector * zoom
                                val rotatedVector = scaledVector.rotateBy(rotation)
                                center + rotatedVector
                            }
                            viewModel.onStickerCornersChange(newCorners)
                        }
                    }
                }
                .pointerInput(uiState.stickerCorners) { // Gesture detector for dragging individual corners.
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            // Find the corner closest to the drag start position.
                            draggedCornerIndex = uiState.stickerCorners
                                .map { corner -> (corner - startOffset).getDistance() }
                                .withIndex()
                                .minByOrNull { it.value }
                                ?.takeIf { it.value < 60f } // Use a 60px touch radius.
                                ?.index
                        },
                        onDragEnd = {
                            draggedCornerIndex = null
                        }
                    ) { change, dragAmount ->
                        draggedCornerIndex?.let { index ->
                            // Update the position of the dragged corner.
                            val newCorners = uiState.stickerCorners.toMutableList()
                            newCorners[index] = newCorners[index] + dragAmount
                            viewModel.onStickerCornersChange(newCorners)
                            change.consume()
                        }
                    }
                }
        ) {
            // Draw the perspective-warped bitmap if it's loaded and corners are defined.
            overlayBitmap?.let { bmp ->
                if (uiState.stickerCorners.size == 4) {
                    val matrix = Matrix()
                    // Source points are the four corners of the original bitmap.
                    val src = floatArrayOf(
                        0f, 0f,
                        bmp.width.toFloat(), 0f,
                        bmp.width.toFloat(), bmp.height.toFloat(),
                        0f, bmp.height.toFloat()
                    )
                    // Destination points are the four user-controlled sticker corners.
                    val dst = floatArrayOf(
                        uiState.stickerCorners[0].x, uiState.stickerCorners[0].y,
                        uiState.stickerCorners[1].x, uiState.stickerCorners[1].y,
                        uiState.stickerCorners[2].x, uiState.stickerCorners[2].y,
                        uiState.stickerCorners[3].x, uiState.stickerCorners[3].y
                    )

                    // Calculate the perspective transformation matrix.
                    matrix.setPolyToPoly(src, 0, dst, 0, 4)

                    // Apply the matrix to the bitmap on the canvas.
                    drawIntoCanvas {
                        it.nativeCanvas.drawBitmap(bmp, matrix, null)
                    }
                }
            }

            // Draw the draggable handles on top of the overlay.
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
 * A private helper extension function to rotate a 2D [Offset] vector by a given angle in degrees.
 *
 * @param degrees The angle of rotation in degrees.
 * @return The new [Offset] vector after rotation.
 */
private fun Offset.rotateBy(degrees: Float): Offset {
    val angleRad = Math.toRadians(degrees.toDouble())
    val cos = kotlin.math.cos(angleRad).toFloat()
    val sin = kotlin.math.sin(angleRad).toFloat()
    return Offset(x * cos - y * sin, x * sin + y * cos)
}