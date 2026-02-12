package com.hereliesaz.graffitixr.feature.editor

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.design.detectSmartOverlayGestures
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope

@Composable
fun TraceScreen(
    uiState: EditorUiState,
    isFlashlightOn: Boolean, // Added to keep signature parity with OverlayScreen
    onOverlayImageSelected: (Uri) -> Unit,
    onCycleRotationAxis: () -> Unit,
    onGestureStart: () -> Unit,
    onGestureEnd: (Float, Offset, Float, Float, Float) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Use rememberUpdatedState to ensure the gesture detector sees the latest UI state
    // without having to restart the pointerInput block.
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

    val colorMatrix = remember(saturation, contrast, brightness, colorBalanceR, colorBalanceG, colorBalanceB) {
        createColorMatrix(saturation, contrast, brightness, colorBalanceR, colorBalanceG, colorBalanceB)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .background(Color.Black)
    ) {
        // Background Image (The surface being traced upon)
        // Hidden during capture (export)
        if (!uiState.hideUiForCapture) {
            uiState.backgroundImageUri?.let {
                AsyncImage(
                    model = it,
                    contentDescription = "Background Image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }

        // Gesture handling box covering the screen
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(uiState.isImageLocked, currentUiState.layers.size) {
                        if (!uiState.isImageLocked) {
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
                    } else {
                        val pointerInputScope = this
                        coroutineScope {
                            launch {
                                pointerInputScope.detectTapGestures(onDoubleTap = { onCycleRotationAxis() })
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
