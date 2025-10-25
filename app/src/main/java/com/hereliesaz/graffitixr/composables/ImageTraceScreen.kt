package com.hereliesaz.graffitixr.composables

import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.hereliesaz.graffitixr.UiState

/**
 * A composable that displays a live camera feed with a movable, scalable, and adjustable image overlay.
 *
 * @param uiState The current UI state.
 * @param onScaleChanged A callback invoked when the user performs a pinch-to-zoom gesture.
 * @param onOffsetChanged A callback invoked when the user performs a pan/drag gesture.
 * @param modifier The modifier to be applied to the layout.
 */
@Composable
fun ImageTraceScreen(
    uiState: UiState,
    onScaleChanged: (Float) -> Unit,
    onOffsetChanged: (androidx.compose.ui.geometry.Offset) -> Unit,
    onRotationZChanged: (Float) -> Unit,
    onRotationXChanged: (Float) -> Unit,
    onRotationYChanged: (Float) -> Unit,
    onCycleRotationAxis: () -> Unit,
    modifier: Modifier = Modifier
) {
    ConstraintBox {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var gestureInProgress by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        // CameraX Preview
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val executor = ContextCompat.getMainExecutor(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview
                    )
                }, executor)
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Interactive Overlay Image
        (uiState.processedImageUri ?: uiState.overlayImageUri)?.let {
            val transformState = rememberTransformableState { zoomChange, offsetChange, rotationChange ->
                onScaleChanged(zoomChange)
                onOffsetChanged(offsetChange)
                when (uiState.activeRotationAxis) {
                    com.hereliesaz.graffitixr.RotationAxis.X -> onRotationXChanged(rotationChange)
                    com.hereliesaz.graffitixr.RotationAxis.Y -> onRotationYChanged(rotationChange)
                    com.hereliesaz.graffitixr.RotationAxis.Z -> onRotationZChanged(rotationChange)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { gestureInProgress = true },
                            onDragEnd = { gestureInProgress = false }
                        ) { _, _ -> }
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
                            translationX = uiState.offset.x,
                            translationY = uiState.offset.y,
                            rotationX = uiState.rotationX,
                            rotationY = uiState.rotationY,
                            rotationZ = uiState.rotationZ,
                            alpha = uiState.opacity
                        )
                        .transformable(state = transformState)
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { onCycleRotationAxis() })
                        },
                    colorFilter = ColorFilter.colorMatrix(
                        ColorMatrix().apply {
                            setToSaturation(uiState.saturation)
                            val contrastMatrix = ColorMatrix(
                                floatArrayOf(
                                    uiState.contrast, 0f, 0f, 0f, (1 - uiState.contrast) * 128,
                                    0f, uiState.contrast, 0f, 0f, (1 - uiState.contrast) * 128,
                                    0f, 0f, uiState.contrast, 0f, (1 - uiState.contrast) * 128,
                                    0f, 0f, 0f, 1f, 0f
                                )
                            )
                            val colorBalanceMatrix = ColorMatrix(
                                floatArrayOf(
                                    uiState.colorBalanceR, 0f, 0f, 0f, 0f,
                                    0f, uiState.colorBalanceG, 0f, 0f, 0f,
                                    0f, 0f, uiState.colorBalanceB, 0f, 0f,
                                    0f, 0f, 0f, 1f, 0f
                                )
                            )
                            this *= contrastMatrix
                            this *= colorBalanceMatrix
                        }
                    )
                )
            }
        }
        if (gestureInProgress) {
            val rotationValue = when (uiState.activeRotationAxis) {
                com.hereliesaz.graffitixr.RotationAxis.X -> uiState.rotationX
                com.hereliesaz.graffitixr.RotationAxis.Y -> uiState.rotationY
                com.hereliesaz.graffitixr.RotationAxis.Z -> uiState.rotationZ
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Scale: %.2f, Rotation (%s): %.1f°".format(uiState.scale, uiState.activeRotationAxis.name, rotationValue),
                    color = Color.White
                )
            }
        }
    }
}
}