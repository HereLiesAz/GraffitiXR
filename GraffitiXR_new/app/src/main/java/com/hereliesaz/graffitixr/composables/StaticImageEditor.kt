package com.hereliesaz.graffitixr.composables

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.geometry.Offset
import android.graphics.Matrix
import androidx.compose.ui.graphics.asComposeMatrix
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hereliesaz.graffitixr.UiState

/**
 * Composable for the mock-up mode on a static background image.
 * This screen will allow users to warp and edit an overlay image on a static background.
 *
 * @param uiState The current state of the UI.
 * @param onBackgroundImageSelected Callback for when a background image is selected.
 * @param onOverlayImageSelected Callback for when an overlay image is selected.
 * @param onOpacityChanged Callback for when the opacity is changed.
 * @param onContrastChanged Callback for when the contrast is changed.
 * @param onSaturationChanged Callback for when the saturation is changed.
 * @param onScaleChanged Callback for when the scale is changed.
 * @param onRotationChanged Callback for when the rotation is changed.
 * @param onPointsInitialized Callback for when the corner points are initialized.
 * @param onPointChanged Callback for when a corner point is changed.
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

    val colorMatrix = ColorMatrix().apply {
        setToSaturation(uiState.saturation)
        val contrast = uiState.contrast
        val contrastMatrix = floatArrayOf(
            contrast, 0f, 0f, 0f, (1 - contrast) * 128,
            0f, contrast, 0f, 0f, (1 - contrast) * 128,
            0f, 0f, contrast, 0f, (1 - contrast) * 128,
            0f, 0f, 0f, 1f, 0f
        )
        postConcat(ColorMatrix(contrastMatrix))
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
                val perspectiveMatrix = remember(uiState.points) {
                    Matrix().apply {
                        if (uiState.points.size == 4) {
                            val (w, h) = uiState.points[2] - uiState.points[0]
                            setPolyToPoly(
                                floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h),
                                0,
                                uiState.points.flatMap { listOf(it.x, it.y) }.toFloatArray(),
                                0,
                                4
                            )
                        }
                    }
                }

                AsyncImage(
                    model = it,
                    contentDescription = "Overlay Image",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = uiState.scale,
                            scaleY = uiState.scale,
                            rotationZ = uiState.rotation
                        )
                        .graphicsLayer {
                            transformations.setFrom(perspectiveMatrix.asComposeMatrix())
                        },
                    alpha = uiState.opacity,
                    colorFilter = ColorFilter.colorMatrix(colorMatrix)
                )

                // Draw the draggable points
                if (uiState.points.isNotEmpty()) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        uiState.points.forEachIndexed { index, offset ->
                            drawCircle(
                                color = Color.White,
                                radius = 20f,
                                center = offset,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f)
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
                        PickVisualMedia.Request(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }) {
                    Text("Select Background")
                }
                Button(onClick = {
                    overlayPickerLauncher.launch(
                        PickVisualMedia.Request(ActivityResultContracts.PickVisualMedia.ImageOnly)
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