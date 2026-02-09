package com.hereliesaz.graffitixr.feature.editor

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
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.design.detectSmartOverlayGestures
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException

@Composable
fun OverlayScreen(
    uiState: EditorUiState,
    isFlashlightOn: Boolean,
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

    LaunchedEffect(isFlashlightOn, camera) {
        try {
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                camera?.cameraControl?.enableTorch(isFlashlightOn)
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
        createColorMatrix(saturation, contrast, brightness, colorBalanceR, colorBalanceG, colorBalanceB)
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

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { containerSize = it }
                .clipToBounds()
                .pointerInput(currentUiState.activeLayerId, currentUiState.layers.size) {
                    val pointerInputScope = this
                    coroutineScope {
                        launch {
                            pointerInputScope.detectTapGestures(onDoubleTap = { onCycleRotationAxis() })
                        }

                        launch {
                            pointerInputScope.detectSmartOverlayGestures(
                                getValidBounds = {
                                    Rect(0f, 0f, size.width.toFloat(), size.height.toFloat())
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
                    }
                }
        ) {
            uiState.layers.forEach { layer ->
                if (layer.isVisible) {
                    val isLayerActive = layer.id == activeLayer?.id
                    val layerScale = if (isLayerActive) transformState.scale else layer.scale
                    val layerOffset = if (isLayerActive) transformState.offset else layer.offset
                    val layerRotationX = if (isLayerActive) transformState.rotationX else layer.rotationX
                    val layerRotationY = if (isLayerActive) transformState.rotationY else layer.rotationY
                    val layerRotationZ = if (isLayerActive) transformState.rotationZ else layer.rotationZ

                    var layerBitmap by remember(layer.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }

                    LaunchedEffect(layer.uri) {
                        launch(Dispatchers.IO) {
                            val request = ImageRequest.Builder(context)
                                .data(layer.uri)
                                .build()
                            val result = (context.imageLoader.execute(request).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                            withContext(Dispatchers.Main) {
                                layerBitmap = result
                            }
                        }
                    }

                    val layerColorMatrix = remember(layer.saturation, layer.contrast, layer.brightness, layer.colorBalanceR, layer.colorBalanceG, layer.colorBalanceB) {
                        createColorMatrix(layer.saturation, layer.contrast, layer.brightness, layer.colorBalanceR, layer.colorBalanceG, layer.colorBalanceB)
                    }

                    layerBitmap?.let { bmp ->
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = layerScale
                                    scaleY = layerScale
                                    this.rotationX = layerRotationX
                                    this.rotationY = layerRotationY
                                    this.rotationZ = layerRotationZ
                                    translationX = layerOffset.x
                                    translationY = layerOffset.y
                                }
                        ) {
                            val xOffset = (size.width - bmp.width) / 2f
                            val yOffset = (size.height - bmp.height) / 2f

                            drawImage(
                                image = bmp.asImageBitmap(),
                                topLeft = Offset(xOffset, yOffset),
                                alpha = layer.opacity,
                                colorFilter = ColorFilter.colorMatrix(layerColorMatrix),
                                blendMode = layer.blendMode
                            )
                        }
                    }
                }
            }
        }
    }
}
