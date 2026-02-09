package com.hereliesaz.graffitixr.feature.editor

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
import androidx.compose.ui.zIndex
import com.hereliesaz.graffitixr.design.detectSmartOverlayGestures
import com.hereliesaz.graffitixr.common.model.RotationAxis
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import coil.imageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun MockupScreen(
    uiState: EditorUiState,
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
    val activeLayer = uiState.activeLayer
    val transformState = rememberLayerTransformState(activeLayer)

    // Local State for background gestures
    val backgroundTransformState = remember(uiState.isEditingBackground) {
        LayerTransformState(
            initialScale = uiState.backgroundScale,
            initialOffset = uiState.backgroundOffset,
            initialRotationX = 0f,
            initialRotationY = 0f,
            initialRotationZ = 0f
        )
    }

    LaunchedEffect(uiState.backgroundScale, uiState.backgroundOffset) {
        if (!backgroundTransformState.isGesturing) {
            backgroundTransformState.scale = uiState.backgroundScale
            backgroundTransformState.offset = uiState.backgroundOffset
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        uiState.backgroundImageUri?.let { uri ->
            AsyncImage(
                model = uri,
                contentDescription = "Background",
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(0f)
                    .graphicsLayer {
                        scaleX = backgroundTransformState.scale
                        scaleY = backgroundTransformState.scale
                        translationX = backgroundTransformState.offset.x
                        translationY = backgroundTransformState.offset.y
                    },
                contentScale = ContentScale.Fit
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1f)
                .onSizeChanged { containerSize = it }
                .clipToBounds()
                // Layer 2: Smart Gestures (Targeting Active Layer or Background)
                .pointerInput(currentUiState.activeLayerId, currentUiState.layers.size, currentUiState.isEditingBackground) {
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
                                    if (currentUiState.isEditingBackground) {
                                        backgroundTransformState.isGesturing = true
                                    } else {
                                        transformState.isGesturing = true
                                    }
                                    onGestureStart()
                                },
                                onGestureEnd = {
                                    if (currentUiState.isEditingBackground) {
                                        backgroundTransformState.isGesturing = false
                                        // We only use scale and offset for background
                                        onGestureEnd(backgroundTransformState.scale, backgroundTransformState.offset, 0f, 0f, 0f)
                                    } else {
                                        transformState.isGesturing = false
                                        onGestureEnd(transformState.scale, transformState.offset, transformState.rotationX, transformState.rotationY, transformState.rotationZ)
                                    }
                                }
                            ) { _, pan, zoom, rotation ->
                                if (currentUiState.isEditingBackground) {
                                    backgroundTransformState.scale *= zoom
                                    backgroundTransformState.offset += pan
                                    // Background rotation not supported yet
                                } else {
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
                }
        ) {
            // Only show layers if NOT editing background
            if (!uiState.isEditingBackground) {
                uiState.layers.forEach { layer ->
                    if (layer.isVisible) {
                        val isLayerActive = layer.id == activeLayer?.id
                        val scale = if (isLayerActive) transformState.scale else layer.scale
                        val offset = if (isLayerActive) transformState.offset else layer.offset
                        val rotationX = if (isLayerActive) transformState.rotationX else layer.rotationX
                        val rotationY = if (isLayerActive) transformState.rotationY else layer.rotationY
                        val rotationZ = if (isLayerActive) transformState.rotationZ else layer.rotationZ

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
                            createColorMatrix(layer.saturation, layer.contrast, layer.brightness, layer.colorBalanceR, layer.colorBalanceG, layer.colorBalanceB)
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
}
