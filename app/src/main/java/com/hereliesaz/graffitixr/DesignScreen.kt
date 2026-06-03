package com.hereliesaz.graffitixr

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.design.detectSmartOverlayGestures
import com.hereliesaz.graffitixr.feature.editor.DrawingCanvas
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import com.hereliesaz.graffitixr.feature.editor.createColorMatrix

@Composable
fun DesignScreen(
    uiState: EditorUiState,
    editorViewModel: EditorViewModel,
    modifier: Modifier = Modifier
) {
    val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }
    val isImageLocked = activeLayer?.isImageLocked ?: false

    Box(modifier = modifier
        .fillMaxSize()
        .background(uiState.canvasBackground)
    ) {
        // Render all layers
        uiState.layers.filter { it.isVisible }.forEach { layer ->
            val isLive = layer.id == uiState.liveStrokeLayerId
            val bmp = if (isLive) uiState.liveStrokeBitmap ?: layer.bitmap else layer.bitmap
            bmp?.let { displayBmp ->
                val imageBitmap = remember(displayBmp, isLive, uiState.liveStrokeVersion) {
                    displayBmp.asImageBitmap()
                }
                Image(
                    bitmap = imageBitmap,
                    contentDescription = null,
                    colorFilter = ColorFilter.colorMatrix(
                        createColorMatrix(
                            saturation = layer.saturation,
                            contrast = layer.contrast,
                            brightness = layer.brightness,
                            colorBalanceR = layer.colorBalanceR,
                            colorBalanceG = layer.colorBalanceG,
                            colorBalanceB = layer.colorBalanceB,
                            isInverted = layer.isInverted
                        )
                    ),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = layer.offset.x
                            translationY = layer.offset.y
                            scaleX = layer.scale
                            scaleY = layer.scale
                            rotationX = layer.rotationX
                            rotationY = layer.rotationY
                            rotationZ = layer.rotationZ
                            alpha = layer.opacity
                            transformOrigin = TransformOrigin.Center
                            blendMode = layer.blendMode
                            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                        },
                    contentScale = ContentScale.Fit
                )
            }
        }

        // Gesture handling for selection and transform
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(uiState.activeLayerId, isImageLocked, uiState.activeTool) {
                    if (!isImageLocked && uiState.activeTool == Tool.NONE) {
                        detectTapGestures(
                            onDoubleTap = { editorViewModel.onCycleRotationAxis() },
                            onTap = {
                                editorViewModel.onDismissPanel()
                                // Logic for clicking to select layer could go here if implemented
                            }
                        )
                    }
                }
                .pointerInput(uiState.activeLayerId, isImageLocked, uiState.activeTool) {
                    if (!isImageLocked && activeLayer != null && uiState.activeTool == Tool.NONE) {
                        detectSmartOverlayGestures(
                            getValidBounds = { androidx.compose.ui.geometry.Rect(0f, 0f, size.width.toFloat(), size.height.toFloat()) },
                            onGestureStart = { editorViewModel.onGestureStart() },
                            onGestureEnd = { editorViewModel.onGestureEnd() },
                            onGesture = { _, pan, zoom, rotation ->
                                editorViewModel.onTransformGesture(pan, zoom, rotation)
                            }
                        )
                    }
                }
        )

        // Drawing Canvas
        if (!isImageLocked && activeLayer != null && uiState.activeTool != Tool.NONE) {
            DrawingCanvas(
                activeTool = uiState.activeTool,
                brushSize = uiState.brushSize,
                activeColor = uiState.activeColor,
                layerBitmapKey = activeLayer.bitmap,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = activeLayer.offset.x
                        translationY = activeLayer.offset.y
                        scaleX = activeLayer.scale
                        scaleY = activeLayer.scale
                        rotationX = activeLayer.rotationX
                        rotationY = activeLayer.rotationY
                        rotationZ = activeLayer.rotationZ
                        transformOrigin = TransformOrigin.Center
                    },
                onStrokeStart = { offset, size -> editorViewModel.onStrokeStart(offset, size) },
                onStrokePoint = { offset -> editorViewModel.onStrokePoint(offset) },
                onStrokeEnd = { editorViewModel.onStrokeEnd() }
            )
        }
    }
}
