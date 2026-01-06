package com.hereliesaz.graffitixr.composables

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
    onScaleChanged: (Float) -> Unit,
    onRotationZChanged: (Float) -> Unit,
    onRotationXChanged: (Float) -> Unit,
    onRotationYChanged: (Float) -> Unit,
    onOffsetChanged: (Offset) -> Unit,
    onCycleRotationAxis: () -> Unit,
    onGestureStart: () -> Unit,
    onGestureEnd: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val currentUiState by rememberUpdatedState(uiState)

    val colorMatrix = remember(uiState.saturation, uiState.contrast, uiState.brightness, uiState.colorBalanceR, uiState.colorBalanceG, uiState.colorBalanceB) {
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

            val b = uiState.brightness * 255f
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
                    uiState.colorBalanceR, 0f, 0f, 0f, 0f,
                    0f, uiState.colorBalanceG, 0f, 0f, 0f,
                    0f, 0f, uiState.colorBalanceB, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            timesAssign(contrastMatrix)
            timesAssign(brightnessMatrix)
            timesAssign(colorBalanceMatrix)
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

        // Multi-Layer Rendering
        // Note: For simplicity in Mockup mode, we iterate and render each layer in a Box.
        // Gestures only apply to the ACTIVE layer.

        // Use layers list if available, otherwise fallback to single image logic
        val layersToRender = if (uiState.layers.isNotEmpty()) uiState.layers else if (uiState.overlayImageUri != null) {
            listOf(
                com.hereliesaz.graffitixr.data.OverlayLayer(
                    id = "default",
                    name = "Default",
                    uri = uiState.overlayImageUri,
                    scale = uiState.scale,
                    rotationX = uiState.rotationX,
                    rotationY = uiState.rotationY,
                    rotationZ = uiState.rotationZ,
                    offset = uiState.offset,
                    opacity = uiState.opacity,
                    brightness = uiState.brightness,
                    contrast = uiState.contrast,
                    saturation = uiState.saturation,
                    colorBalanceR = uiState.colorBalanceR,
                    colorBalanceG = uiState.colorBalanceG,
                    colorBalanceB = uiState.colorBalanceB,
                    blendMode = uiState.blendMode
                )
            )
        } else emptyList()

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
                .pointerInput(currentUiState.activeLayerId, layersToRender.size) {
                    // Logic to find active layer bitmap size is complex here without loading it first.
                    // For now, we allow gestures globally and apply to active layer logic in callbacks.
                    detectSmartOverlayGestures(
                        getValidBounds = {
                            // Simplified bounds: Full screen or active layer bounds if possible
                            // Ideally we get the active layer's bitmap size.
                            Rect(0f, 0f, size.width.toFloat(), size.height.toFloat())
                        },
                        onGestureStart = onGestureStart,
                        onGestureEnd = onGestureEnd
                    ) { _, pan, zoom, rotation ->
                        onScaleChanged(zoom)
                        onOffsetChanged(pan)
                        when (currentUiState.activeRotationAxis) {
                            RotationAxis.X -> onRotationXChanged(rotation)
                            RotationAxis.Y -> onRotationYChanged(rotation)
                            RotationAxis.Z -> onRotationZChanged(rotation)
                        }
                    }
                }
        ) {
            layersToRender.forEach { layer ->
                if (layer.isVisible) {
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
                                    scaleX = layer.scale
                                    scaleY = layer.scale
                                    rotationX = layer.rotationX
                                    rotationY = layer.rotationY
                                    rotationZ = layer.rotationZ
                                    translationX = layer.offset.x
                                    translationY = layer.offset.y
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