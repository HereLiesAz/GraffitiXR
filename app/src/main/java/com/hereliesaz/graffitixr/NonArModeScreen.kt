package com.hereliesaz.graffitixr

import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.rememberAsyncImagePainter

/**
 * A composable function that provides a non-AR camera overlay experience.
 *
 * This screen displays a live feed from the device's camera and overlays the user-selected
 * image on top of it. It does not perform any AR tracking or surface detection. The overlay
 * image's properties (opacity, contrast, saturation) can be adjusted in real-time.
 *
 * @param modifier A [Modifier] for this composable.
 * @param uiState The current [UiState] of the application, which provides the image URI
 *   and adjustment values.
 */
@Composable
fun NonArModeScreen(modifier: Modifier = Modifier, uiState: UiState) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraController = remember { LifecycleCameraController(context) }

    // A box to stack the camera preview and the overlay image.
    Box(modifier = modifier.fillMaxSize()) {
        // The camera preview, which fills the entire screen.
        AndroidView(
            factory = {
                PreviewView(it).apply {
                    this.controller = cameraController
                    cameraController.bindToLifecycle(lifecycleOwner)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // The overlay image, displayed if a URI is available.
        uiState.imageUri?.let {
            // Create a color matrix to adjust contrast and saturation.
            val colorMatrix = ColorMatrix().apply {
                setToSaturation(uiState.saturation)
                times(
                    ColorMatrix().apply {
                        // A simple contrast formula.
                        val scale = uiState.contrast
                        val translate = (-0.5f * scale + 0.5f) * 255f
                        set(
                            floatArrayOf(
                                scale, 0f, 0f, 0f, translate,
                                0f, scale, 0f, 0f, translate,
                                0f, 0f, scale, 0f, translate,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                    }
                )
            }

            Image(
                painter = rememberAsyncImagePainter(it),
                contentDescription = "Overlay Image",
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(uiState.opacity),
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.colorMatrix(colorMatrix)
            )
        }
    }
}