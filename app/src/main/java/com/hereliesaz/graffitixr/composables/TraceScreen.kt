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

    // Use rememberUpdatedState to ensure the gesture detector sees the latest UI state
    // without having to restart the pointerInput block.
    val currentUiState by rememberUpdatedState(uiState)

    val colorMatrix = remember(uiState.saturation, uiState.contrast, uiState.colorBalanceR, uiState.colorBalanceG, uiState.colorBalanceB) {
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
            val colorBalanceMatrix = ColorMatrix(
                floatArrayOf(
                    uiState.colorBalanceR, 0f, 0f, 0f, 0f,
                    0f, uiState.colorBalanceG, 0f, 0f, 0f,
                    0f, 0f, uiState.colorBalanceB, 0f, 0f,
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
        uiState.overlayImageUri?.let { uri ->
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
                                // Calculate bounds dynamically using the LATEST state
                                val state = currentUiState
                                val imgWidth = bmp.width * state.scale
                                val imgHeight = bmp.height * state.scale
                                val centerX = size.width / 2f + state.offset.x
                                val centerY = size.height / 2f + state.offset.y
                                val left = centerX - imgWidth / 2f
                                val top = centerY - imgHeight / 2f
                                Rect(left, top, left + imgWidth, top + imgHeight)
                            },
                            onGestureStart = onGestureStart,
                            onGestureEnd = onGestureEnd
                        ) { _, pan, zoom, rotation ->
                            onScaleChanged(zoom)
                            onOffsetChanged(pan)
                            // Use currentUiState to get the active axis at the moment of rotation
                            when (currentUiState.activeRotationAxis) {
                                RotationAxis.X -> onRotationXChanged(rotation)
                                RotationAxis.Y -> onRotationYChanged(rotation)
                                RotationAxis.Z -> onRotationZChanged(rotation)
                            }
                        }
                    }
            ) {
                imageBitmap?.let { bmp ->
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = uiState.scale
                                scaleY = uiState.scale
                                rotationX = uiState.rotationX
                                rotationY = uiState.rotationY
                                rotationZ = uiState.rotationZ
                                translationX = uiState.offset.x
                                translationY = uiState.offset.y
                            }
                    ) {
                        val xOffset = (size.width - bmp.width) / 2f
                        val yOffset = (size.height - bmp.height) / 2f

                        drawImage(
                            image = bmp.asImageBitmap(),
                            topLeft = Offset(xOffset, yOffset),
                            alpha = uiState.opacity,
                            colorFilter = ColorFilter.colorMatrix(colorMatrix),
                            blendMode = uiState.blendMode
                        )
                    }
                }
            }
        }
    }
}