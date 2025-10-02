package com.hereliesaz.graffitixr.composables

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hereliesaz.graffitixr.UiState

/**
 * Composable for the mock-up mode on a static background image.
 * This screen will allow users to warp and edit an overlay image on a static background.
 */
@Composable
fun StaticImageEditor(
    uiState: UiState,
    onBackgroundImageSelected: (Uri) -> Unit,
    onOverlayImageSelected: (Uri) -> Unit,
    onOpacityChanged: (Float) -> Unit,
    onContrastChanged: (Float) -> Unit,
    onSaturationChanged: (Float) -> Unit,
    onScaleChanged: (Float) -> Unit,
    onRotationChanged: (Float) -> Unit,
    onPointsInitialized: (List<Offset>) -> Unit,
    onPointChanged: (Int, Offset) -> Unit
) {
    val backgroundPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { onBackgroundImageSelected(it) }
    }

    val overlayPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { onOverlayImageSelected(it) }
    }

    val colorMatrix = remember(uiState.saturation, uiState.contrast) {
        ColorMatrix().apply {
            setToSaturation(uiState.saturation)
            val contrast = uiState.contrast
            val contrastMatrix = ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, (1 - contrast) * 128,
                    0f, contrast, 0f, 0f, (1 - contrast) * 128,
                    0f, 0f, contrast, 0f, (1 - contrast) * 128,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            timesAssign(contrastMatrix)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Display the background image if selected
        uiState.backgroundImageUri?.let {
            AsyncImage(
                model = it,
                contentDescription = "Background Image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        // Display the overlay image if selected
        uiState.overlayImageUri?.let {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coordinates ->
                        if (uiState.points.isEmpty()) {
                            val size = coordinates.size
                            val points = listOf(
                                Offset(0f, 0f),
                                Offset(size.width.toFloat(), 0f),
                                Offset(size.width.toFloat(), size.height.toFloat()),
                                Offset(0f, size.height.toFloat())
                            )
                            onPointsInitialized(points)
                        }
                    }
            ) {
                // TODO: Restore perspective warp. The `graphicsLayer` API has changed, and
                //  there is no direct replacement for applying a 2D perspective matrix.
                //  A custom solution using a combination of other modifiers or a custom
                //  layout would be required to re-implement this feature.

                AsyncImage(
                    model = it,
                    contentDescription = "Overlay Image",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = uiState.scale,
                            scaleY = uiState.scale,
                            rotationZ = uiState.rotation
                        ),
                    alpha = uiState.opacity,
                    colorFilter = ColorFilter.colorMatrix(colorMatrix)
                )

                // Draw the draggable points
                if (uiState.points.isNotEmpty()) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        uiState.points.forEachIndexed { _, offset ->
                            drawCircle(
                                color = Color.White,
                                radius = 20f,
                                center = offset,
                                style = Stroke(width = 5f)
                            )
                        }
                    }

                    // Add draggable handles
                    uiState.points.forEachIndexed { index, offset ->
                        Box(
                            modifier = Modifier
                                .offset(offset.x.dp, offset.y.dp)
                                .size(40.dp)
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        onPointChanged(index, offset + dragAmount)
                                    }
                                }
                        )
                    }
                }
            }
        }

        // Control Panel
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(onClick = {
                    backgroundPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }) {
                    Text("Select Background")
                }
                Button(onClick = {
                    overlayPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }) {
                    Text("Select Overlay")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Opacity Slider
            Text("Opacity", color = Color.White)
            Slider(value = uiState.opacity, onValueChange = onOpacityChanged)

            // Contrast Slider
            Text("Contrast", color = Color.White)
            Slider(value = uiState.contrast, onValueChange = onContrastChanged, valueRange = 0f..2f)

            // Saturation Slider
            Text("Saturation", color = Color.White)
            Slider(value = uiState.saturation, onValueChange = onSaturationChanged, valueRange = 0f..2f)
        }
    }
}