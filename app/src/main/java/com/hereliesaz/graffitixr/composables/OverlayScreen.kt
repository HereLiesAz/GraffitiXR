package com.hereliesaz.graffitixr.composables

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.hereliesaz.graffitixr.RotationAxis
import com.hereliesaz.graffitixr.UiState

@Composable
fun OverlayScreen(
    uiState: UiState,
    onScaleChanged: (Float) -> Unit,
    onOffsetChanged: (Offset) -> Unit,
    onRotationZChanged: (Float) -> Unit,
    onRotationXChanged: (Float) -> Unit,
    onRotationYChanged: (Float) -> Unit,
    onCycleRotationAxis: () -> Unit,
    onGestureStart: () -> Unit,
    onGestureEnd: () -> Unit
) {
    val context = LocalContext.current
    val imageUri = uiState.overlayImageUri ?: uiState.processedImageUri

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent) // See-through to camera or other app
    ) {
        if (imageUri != null) {
            // Apply color adjustments
            val colorMatrix = ColorMatrix().apply {
                setToSaturation(uiState.saturation)
            }
            // Simple brightness/contrast approximation for ColorMatrix:
            // This is a rough approximation; for precise control we'd use a custom shader or more complex matrix math.
            // Brightness (offset): matrix[4, 9, 14]
            // Contrast (scale): matrix[0, 6, 12]
            val contrastScale = uiState.contrast
            val brightnessOffset = uiState.brightness * 255f

            val brightnessMatrix = floatArrayOf(
                contrastScale, 0f, 0f, 0f, brightnessOffset,
                0f, contrastScale, 0f, 0f, brightnessOffset,
                0f, 0f, contrastScale, 0f, brightnessOffset,
                0f, 0f, 0f, 1f, 0f
            )
            colorMatrix.timesAssign(ColorMatrix(brightnessMatrix))

            val colorBalanceMatrix = floatArrayOf(
                uiState.colorBalanceR, 0f, 0f, 0f, 0f,
                0f, uiState.colorBalanceG, 0f, 0f, 0f,
                0f, 0f, uiState.colorBalanceB, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
            colorMatrix.timesAssign(ColorMatrix(colorBalanceMatrix))


            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageUri)
                    .crossfade(true)
                    .build(),
                contentDescription = "Overlay Image",
                contentScale = ContentScale.Fit, // Or FillBounds depending on preference
                colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(colorMatrix),
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(uiState.opacity)
                    .graphicsLayer {
                        scaleX = uiState.scale
                        scaleY = uiState.scale
                        rotationZ = uiState.rotationZ
                        rotationX = uiState.rotationX
                        rotationY = uiState.rotationY
                        translationX = uiState.offset.x
                        translationY = uiState.offset.y

                        // Set blend mode
                        this.blendMode = uiState.blendMode

                        // Center pivot for rotations
                        transformOrigin = TransformOrigin.Center
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures(
                            onGesture = { _, pan, zoom, rotation ->
                                onGestureStart()
                                onScaleChanged(zoom)
                                // Invert rotation for natural feel
                                if (uiState.activeRotationAxis == RotationAxis.Z) {
                                     onRotationZChanged(-rotation)
                                }
                                onOffsetChanged(pan)
                                // X/Y rotation not easily mapped to 2D gestures without mode switching
                                // We could use 2-finger vertical/horizontal drag for X/Y if Z is not active,
                                // but standard transform gestures are usually pan/zoom/rotateZ.
                                // For X/Y, we might need a dedicated mode or specialized gesture detector.
                                if (uiState.activeRotationAxis == RotationAxis.X) {
                                    // Map vertical pan to X rotation?
                                    // This conflicts with pan.
                                    // Typically handled by single finger drag in 3D apps, but we have pan.
                                    // Let's stick to the buttons/sliders or a specific 'rotate' mode for X/Y in 2D view if needed.
                                    // However, the prompt implies "Ghost Mode" (Overlay) is 2D over camera.
                                    // 3D rotations on a 2D plane are just skewing/projection.
                                }
                                onGestureEnd()
                            }
                        )
                    }
            )
        } else {
             // Placeholder or empty state
             Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                 // Text("No Image Selected", color = Color.White)
             }
        }
    }
}
