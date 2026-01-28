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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import coil.imageLoader
import coil.request.ImageRequest
import com.hereliesaz.graffitixr.RotationAxis
import com.hereliesaz.graffitixr.UiState
import com.hereliesaz.graffitixr.utils.detectSmartOverlayGestures
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
    val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId } ?: uiState.layers.firstOrNull()

    // Local State for smooth gestures
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

    val scale = currentScale
    val offset = currentOffset
    val rotationX = currentRotationX
    val rotationY = currentRotationY
    val rotationZ = currentRotationZ

    val opacity = activeLayer?.opacity ?: 1f
    val blendMode = activeLayer?.blendMode ?: BlendMode.SrcOver
    val contrast = activeLayer?.contrast ?: 1f
    val saturation = activeLayer?.saturation ?: 1f
    val colorBalanceR = activeLayer?.colorBalanceR ?: 1f
    val colorBalanceG = activeLayer?.colorBalanceG ?: 1f
    val colorBalanceB = activeLayer?.colorBalanceB ?: 1f

    val colorMatrix = remember(saturation, contrast, colorBalanceR, colorBalanceG, colorBalanceB) {
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
            val colorBalanceMatrix = ColorMatrix(
                floatArrayOf(
                    colorBalanceR, 0f, 0f, 0f, 0f,
                    0f, colorBalanceG, 0f, 0f, 0f,
                    0f, 0f, colorBalanceB, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            timesAssign(contrastMatrix)
            timesAssign(colorBalanceMatrix)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        activeLayer?.uri?.let { uri ->
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
                    // Layer 1: Double Tap (Must be first to intercept)
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { onCycleRotationAxis() })
                    }
                    // Layer 2: Unified Smart Gestures
                    // We only restart if the imageBitmap reference changes.
                    // UI state changes (offset, scale, axis) are handled via rememberUpdatedState.
                    .pointerInput(imageBitmap) {
                        val bmp = imageBitmap ?: return@pointerInput

                        detectSmartOverlayGestures(
                            getValidBounds = {
                                // Calculate bounds dynamically using the LOCAL state
                                val imgWidth = bmp.width * currentScale
                                val imgHeight = bmp.height * currentScale
                                val centerX = size.width / 2f + currentOffset.x
                                val centerY = size.height / 2f + currentOffset.y
                                val left = centerX - imgWidth / 2f
                                val top = centerY - imgHeight / 2f
                                Rect(left, top, left + imgWidth, top + imgHeight)
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
                            // Use currentUiState to get the active axis at the moment of rotation
                            when (currentUiState.activeRotationAxis) {
                                RotationAxis.X -> currentRotationX += rotation
                                RotationAxis.Y -> currentRotationY += rotation
                                RotationAxis.Z -> currentRotationZ += rotation
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
