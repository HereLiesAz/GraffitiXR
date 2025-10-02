package com.hereliesaz.graffitixr.composables

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.hereliesaz.graffitixr.UiState

/**
 * Composable for the simple camera overlay mode.
 * This screen is a fallback for devices that do not support AR.
 *
 * @param uiState The current state of the UI.
 * @param onOverlayImageSelected Callback for when an overlay image is selected.
 * @param onOpacityChanged Callback for when the opacity is changed.
 * @param onContrastChanged Callback for when the contrast is changed.
 * @param onSaturationChanged Callback for when the saturation is changed.
 * @param onScaleChanged Callback for when the scale is changed.
 * @param onRotationChanged Callback for when the rotation is changed.
 */
@Composable
fun NonArModeScreen(
    uiState: UiState,
    onOverlayImageSelected: (Uri) -> Unit,
    onOpacityChanged: (Float) -> Unit,
    onContrastChanged: (Float) -> Unit,
    onSaturationChanged: (Float) -> Unit,
    onScaleChanged: (Float) -> Unit,
    onRotationChanged: (Float) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCamPermission by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCamPermission = isGranted
    }

    LaunchedEffect(key1 = true) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
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
        if (hasCamPermission) {
            AndroidView(
                factory = { context ->
                    val previewView = PreviewView(context)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build()
                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                        preview.setSurfaceProvider(previewView.surfaceProvider)
                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner, cameraSelector, preview
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }, ContextCompat.getMainExecutor(context))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Display the overlay image if selected
        uiState.overlayImageUri?.let {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, rotation ->
                            onScaleChanged(zoom)
                            onRotationChanged(rotation)
                        }
                    }
            ) {
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
            }
        }

        // Control Panel
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(16.dp)
        ) {
            Button(
                onClick = {
                    overlayPickerLauncher.launch(
                        ActivityResultContracts.PickVisualMedia.Request(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Select Overlay")
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