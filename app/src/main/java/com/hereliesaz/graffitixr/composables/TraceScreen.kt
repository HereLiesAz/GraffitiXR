package com.hereliesaz.graffitixr.composables

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.ImageRequest
import com.hereliesaz.graffitixr.UiState
import kotlinx.coroutines.launch

/**
 * A composable screen for the "Trace" mode (Lightbox).
 *
 * This screen displays the overlay image on a white background, allowing the device to be used
 * as a lightbox for tracing. It supports standard image manipulations.
 */
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

    // Lightbox background: White
    Box(modifier = Modifier
        .fillMaxSize()
        .background(Color.White)
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
                    .clipToBounds()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { onGestureStart() },
                            onDragEnd = { onGestureEnd() }
                        ) { _, _ -> }
                    }
            ) {
                LaunchedEffect(transformState.isTransformInProgress) {
                    if (transformState.isTransformInProgress) {
                        onGestureStart()
                    } else {
                        onGestureEnd()
                    }
                }
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
                            .transformable(state = transformState)
                            .pointerInput(Unit) {
                                detectTapGestures(onDoubleTap = { onCycleRotationAxis() })
                            }
                    ) {
                        drawImage(
                            image = bmp.asImageBitmap(),
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
