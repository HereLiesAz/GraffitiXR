package com.hereliesaz.graffitixr.composables

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
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
import com.hereliesaz.graffitixr.RotationAxis
import com.hereliesaz.graffitixr.UiState
import com.hereliesaz.graffitixr.utils.detectSmartOverlayGestures
import kotlinx.coroutines.launch

@Composable
fun MockupScreen(
    uiState: UiState,
    onBackgroundImageSelected: (Uri) -> Unit,
    onOverlayImageSelected: (Uri) -> Unit,
    onOpacityChanged: (Float) -> Unit,
    onBrightnessChanged: (Float) -> Unit,
    onContrastChanged: (Float) -> Unit,
    onSaturationChanged: (Float) -> Unit,
    onCycleRotationAxis: () -> Unit,
    onGestureStart: () -> Unit,
    onGestureEnd: (Float, Offset, Float, Float, Float) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val currentUiState by rememberUpdatedState(uiState)

    // Local State for smooth gestures (Active Layer)
    val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }
    var isGesturing by remember { mutableStateOf(false) }
    var currentScale by remember { mutableFloatStateOf(activeLayer?.scale ?: 1f) }
    var currentOffset by remember { mutableStateOf(activeLayer?.offset ?: Offset.Zero) }
    var currentRotationX by remember { mutableFloatStateOf(activeLayer?.rotationX ?: 0f) }
    var currentRotationY by remember { mutableFloatStateOf(activeLayer?.rotationY ?: 0f) }
    var currentRotationZ by remember { mutableFloatStateOf(activeLayer?.rotationZ ?: 0f) }

    // Sync state if not gesturing
    LaunchedEffect(activeLayer) {
        if (!isGesturing && activeLayer != null) {
            currentScale = activeLayer.scale
            currentOffset = activeLayer.offset
            currentRotationX = activeLayer.rotationX
            currentRotationY = activeLayer.rotationY
            currentRotationZ = activeLayer.rotationZ
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        uiState.backgroundImageUri?.let {
            AsyncImage(
                model = it,
                contentDescription = "Background Image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { containerSize = it }
                .clipToBounds()
                // Layer 1: Double Tap (Global)
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { onCycleRotationAxis() })
                }
                // Layer 2: Smart Gestures (Targeting Active Layer)
                .pointerInput(currentUiState.activeLayerId, currentUiState.layers.size) {
                    detectSmartOverlayGestures(
                        getValidBounds = {
                            Rect(0f, 0f, size.width.toFloat(), size.height.toFloat())
                        },
                        onGestureStart = {
                            isGesturing = true
                            onGestureStart()
                        },
                        onGestureEnd = {
                            isGesturing = false
                            onGestureEnd(currentScale, currentOffset, currentRotationX, currentRotationY, currentRotationZ)
                        }
                    ) { _, pan, zoom, rotation ->
                        currentScale *= zoom
                        currentOffset += pan
                        when (currentUiState.activeRotationAxis) {
                            RotationAxis.X -> currentRotationX += rotation
                            RotationAxis.Y -> currentRotationY += rotation
                            RotationAxis.Z -> currentRotationZ += rotation
                        }
                    }
                }
        ) {
            uiState.layers.forEach { layer ->
                if (layer.isVisible) {
                    val isLayerActive = layer.id == uiState.activeLayerId
                    val scale = if (isLayerActive) currentScale else layer.scale
                    val offset = if (isLayerActive) currentOffset else layer.offset
                    val rotationX = if (isLayerActive) currentRotationX else layer.rotationX
                    val rotationY = if (isLayerActive) currentRotationY else layer.rotationY
                    val rotationZ = if (isLayerActive) currentRotationZ else layer.rotationZ

                    var layerBitmap by remember(layer.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }

                    LaunchedEffect(layer.uri) {
                        coroutineScope.launch {
                            val request = ImageRequest.Builder(context)
                                .data(layer.uri)
                                .build()
                            val result = (context.imageLoader.execute(request).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                            layerBitmap = result
                        }
                    }

                    // Calculate color matrix for THIS layer
                    val layerColorMatrix = remember(layer.saturation, layer.contrast, layer.brightness, layer.colorBalanceR, layer.colorBalanceG, layer.colorBalanceB) {
                        ColorMatrix().apply {
                            setToSaturation(layer.saturation)
                            val c = layer.contrast
                            val contrastMatrix = ColorMatrix(
                                floatArrayOf(
                                    c, 0f, 0f, 0f, (1 - c) * 128,
                                    0f, c, 0f, 0f, (1 - c) * 128,
                                    0f, 0f, c, 0f, (1 - c) * 128,
                                    0f, 0f, 0f, 1f, 0f
                                )
                            )
                            val b = layer.brightness * 255f
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
                                    layer.colorBalanceR, 0f, 0f, 0f, 0f,
                                    0f, layer.colorBalanceG, 0f, 0f, 0f,
                                    0f, 0f, layer.colorBalanceB, 0f, 0f,
                                    0f, 0f, 0f, 1f, 0f
                                )
                            )
                            timesAssign(contrastMatrix)
                            timesAssign(brightnessMatrix)
                            timesAssign(colorBalanceMatrix)
                        }
                    }

                    layerBitmap?.let { bmp ->
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
