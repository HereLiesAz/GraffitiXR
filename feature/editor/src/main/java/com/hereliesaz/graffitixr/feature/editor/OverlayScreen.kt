package com.hereliesaz.graffitixr.composables

import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.imageLoader
import coil.request.ImageRequest
import com.hereliesaz.graffitixr.RotationAxis
import com.hereliesaz.graffitixr.UiState
import com.hereliesaz.graffitixr.utils.detectSmartOverlayGestures
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutionException

@Composable
fun OverlayScreen(
    uiState: UiState,
    onCycleRotationAxis: () -> Unit,
    onGestureStart: () -> Unit,
    onGestureEnd: (Float, Offset, Float, Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val currentUiState by rememberUpdatedState(uiState)

    // Resolve Active Layer
    val activeLayer = uiState.activeLayer

    val transformState = rememberLayerTransformState(activeLayer)
    val scale = transformState.scale
    val offset = transformState.offset
    val rotationX = transformState.rotationX
    val rotationY = transformState.rotationY
    val rotationZ = transformState.rotationZ

    val opacity = activeLayer?.opacity ?: 1f
    val blendMode = activeLayer?.blendMode ?: BlendMode.SrcOver
    val contrast = activeLayer?.contrast ?: 1f
    val brightness = activeLayer?.brightness ?: 0f
    val saturation = activeLayer?.saturation ?: 1f
    val colorBalanceR = activeLayer?.colorBalanceR ?: 1f
    val colorBalanceG = activeLayer?.colorBalanceG ?: 1f
    val colorBalanceB = activeLayer?.colorBalanceB ?: 1f

    // Flashlight control logic
    LaunchedEffect(uiState.isFlashlightOn, camera) {
        try {
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                camera?.cameraControl?.enableTorch(uiState.isFlashlightOn)
            }
        } catch (e: Exception) {
            Log.e("OverlayScreen", "Failed to set torch state", e)
        }
    }

    // CRITICAL FIX: Ensure CameraX releases the camera when this screen is disposed
    DisposableEffect(lifecycleOwner) {
        onDispose {
            try {
                // Force unbind all use cases to free the camera for ARCore
                if (cameraProviderFuture.isDone) {
                    cameraProviderFuture.get().unbindAll()
                }
            } catch (e: Exception) {
                Log.e("OverlayScreen", "Failed to unbind camera", e)
            }
        }
    }

    val colorMatrix = remember(saturation, contrast, brightness, colorBalanceR, colorBalanceG, colorBalanceB) {
        ColorMatrix().apply {
            setToSaturation(saturation)
            val contrastMatrix = ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, (1 - contrast) * 128,
                    0f, contrast, 0f, 0f, (1 - contrast) * 128,
                    0f, 0f, contrast, 0f, (1 - contrast) * 128,
                    0f, 0f, 0f, 1f, 0f
                )
            )

            val b = brightness * 255f
            val brightnessMatrix = ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, b,
                    0f, 1f, 0f, 0f, b,
                    0f, 0f, 1f, 0f, b,
                    0f, 0f, 0f, 1f, 0f
                )
            )

            val colorBalanceMatrix = ColorMatrix(
                floatArrayOf(
                    colorBalanceR, 0f, 0f, 0f, 0f,
                    0f, colorBalanceG, 0f, 0f, 0f,
                    0f, 0f, colorBalanceB, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            timesAssign(contrastMatrix)
            timesAssign(brightnessMatrix)
            timesAssign(colorBalanceMatrix)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // CameraX Preview View
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val executor = ContextCompat.getMainExecutor(ctx)
                cameraProviderFuture.addListener({
                    try {
                        val cameraProvider = cameraProviderFuture.get()
                        // Ensure we start fresh
                        cameraProvider.unbindAll()

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                        camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview
                        )
                    } catch (e: Exception) {
                        Log.e("OverlayScreen", "CameraX init failed", e)
                    }
                }, executor)
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        val imageUri = activeLayer?.uri

        imageUri?.let { uri ->
            var imageBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

            LaunchedEffect(uri) {
                coroutineScope.launch {
                    val request = ImageRequest.Builder(context)
                        .data(uri)
                        .build()
                    val result =
                        (context.imageLoader.execute(request).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    imageBitmap = result
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { containerSize = it }
                    .clipToBounds()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { onCycleRotationAxis() })
                    }
                    .pointerInput(imageBitmap) {
                        val bmp = imageBitmap ?: return@pointerInput

                        detectSmartOverlayGestures(
                            getValidBounds = {
                                val imgWidth = bmp.width * transformState.scale
                                val imgHeight = bmp.height * transformState.scale
                                val centerX = size.width / 2f + transformState.offset.x
                                val centerY = size.height / 2f + transformState.offset.y
                                val left = centerX - imgWidth / 2f
                                val top = centerY - imgHeight / 2f
                                Rect(left, top, left + imgWidth, top + imgHeight)
                            },
                            onGestureStart = {
                                transformState.isGesturing = true
                                onGestureStart()
                            },
                            onGestureEnd = {
                                transformState.isGesturing = false
                                onGestureEnd(transformState.scale, transformState.offset, transformState.rotationX, transformState.rotationY, transformState.rotationZ)
                            }
                        ) { _, pan, zoom, rotation ->
                            transformState.scale *= zoom
                            transformState.offset += pan
                            when (currentUiState.activeRotationAxis) {
                                RotationAxis.X -> transformState.rotationX += rotation
                                RotationAxis.Y -> transformState.rotationY += rotation
                                RotationAxis.Z -> transformState.rotationZ += rotation
                            }
                        }
                    }
            ) {
                imageBitmap?.let { bmp ->
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                this.rotationX = rotationX
                                this.rotationY = rotationY
                                this.rotationZ = rotationZ
                                translationX = offset.x
                                translationY = offset.y
                            }
                    ) {
                        val xOffset = (size.width - bmp.width) / 2f
                        val yOffset = (size.height - bmp.height) / 2f

                        drawImage(
                            image = bmp.asImageBitmap(),
                            topLeft = Offset(xOffset, yOffset),
                            alpha = opacity,
                            colorFilter = ColorFilter.colorMatrix(colorMatrix),
                            blendMode = blendMode
                        )
                    }
                }
            }
        }
    }
}