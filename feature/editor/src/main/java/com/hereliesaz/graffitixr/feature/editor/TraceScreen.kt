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
import com.hereliesaz.graffitixr.common.model.OverlayLayer
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
        uiState.backgroundImageUri?.let {
            AsyncImage(
                model = it,
                contentDescription = "Background Image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }

        val imageUri = activeLayer?.uri

        imageUri?.let { uri ->
            var imageBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

            LaunchedEffect(uri) {
                // Use IO dispatcher to avoid blocking main thread
                launch(Dispatchers.IO) {
                    val request = ImageRequest.Builder(context)
                        .data(uri)
                        .build()
                    val result =
                        (context.imageLoader.execute(request).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    withContext(Dispatchers.Main) {
                        imageBitmap = result
                    }
                }
            }

            // Gesture handling box covering the screen
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(uiState.isImageLocked, imageBitmap) {
                         if (!uiState.isImageLocked) {
                            val bmp = imageBitmap ?: return@pointerInput

                            coroutineScope {
                                launch {
                                    detectTapGestures(onDoubleTap = { onCycleRotationAxis() })
                                }

                                launch {
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
                            }
                        } else {
                            coroutineScope {
                                launch {
                                    detectTapGestures(onDoubleTap = { onCycleRotationAxis() })
                                }
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
