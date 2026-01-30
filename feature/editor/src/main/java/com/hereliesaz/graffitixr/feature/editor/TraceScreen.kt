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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import coil.imageLoader
import coil.request.ImageRequest
import com.hereliesaz.graffitixr.design.RotationAxis
import com.hereliesaz.graffitixr.UiState
import com.hereliesaz.graffitixr.common.detectSmartOverlayGestures
import kotlinx.coroutines.launch

@Composable
fun TraceScreen(
    uiState: UiState,
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onCycleRotationAxis() })
            }
            .pointerInput(activeLayer?.id) { // Re-bind gestures if active layer changes
                val activeId = activeLayer?.id ?: return@pointerInput

                detectSmartOverlayGestures(
                    getValidBounds = {
                        // For bounding box, we assume the layer fills/centers reasonably or use 0-size as fallback
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
    ) {
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

                val layerColorMatrix = remember(layer.saturation, layer.contrast, layer.colorBalanceR, layer.colorBalanceG, layer.colorBalanceB) {
                    ColorMatrix().apply {
                        setToSaturation(layer.saturation)
                        val contrastMatrix = ColorMatrix(
                            floatArrayOf(
                                layer.contrast, 0f, 0f, 0f, (1 - layer.contrast) * 128,
                                0f, layer.contrast, 0f, 0f, (1 - layer.contrast) * 128,
                                0f, 0f, layer.contrast, 0f, (1 - layer.contrast) * 128,
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
