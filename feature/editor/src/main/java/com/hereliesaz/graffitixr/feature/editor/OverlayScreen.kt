package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.EditorUiState
import kotlin.math.min

@Composable
fun OverlayScreen(
    uiState: EditorUiState,
    viewModel: EditorViewModel
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        uiState.layers.forEach { layer ->
            if (layer.isVisible) {
                val imageBitmap = layer.bitmap.asImageBitmap()
                val srcWidth = imageBitmap.width.toFloat()
                val srcHeight = imageBitmap.height.toFloat()

                val scaleFactor = min(size.width / srcWidth, size.height / srcHeight)
                val drawnWidth = srcWidth * scaleFactor
                val drawnHeight = srcHeight * scaleFactor
                val topLeftX = (size.width - drawnWidth) / 2
                val topLeftY = (size.height - drawnHeight) / 2

                val centerX = size.width / 2
                val centerY = size.height / 2

                withTransform({
                    translate(layer.offset.x, layer.offset.y)
                    rotate(layer.rotationZ, pivot = Offset(centerX, centerY))
                    scale(layer.scale, layer.scale, pivot = Offset(centerX, centerY))
                }) {
                    drawImage(
                        image = imageBitmap,
                        dstOffset = IntOffset(topLeftX.toInt(), topLeftY.toInt()),
                        dstSize = IntSize(drawnWidth.toInt(), drawnHeight.toInt()),
                        alpha = layer.opacity,
                        blendMode = mapComposeBlendMode(layer.blendMode)
                    )
                }
            }
        }
    }
}

// Maps our domain BlendMode to Android's compose BlendMode.
// Unlike the original lie of omission, Hardlight and Softlight exist natively.
fun mapComposeBlendMode(mode: com.hereliesaz.graffitixr.common.model.BlendMode): BlendMode {
    return when(mode) {
        com.hereliesaz.graffitixr.common.model.BlendMode.SrcOver -> BlendMode.SrcOver
        com.hereliesaz.graffitixr.common.model.BlendMode.Multiply -> BlendMode.Multiply
        com.hereliesaz.graffitixr.common.model.BlendMode.Screen -> BlendMode.Screen
        com.hereliesaz.graffitixr.common.model.BlendMode.Overlay -> BlendMode.Overlay
        com.hereliesaz.graffitixr.common.model.BlendMode.Darken -> BlendMode.Darken
        com.hereliesaz.graffitixr.common.model.BlendMode.Lighten -> BlendMode.Lighten
        com.hereliesaz.graffitixr.common.model.BlendMode.ColorDodge -> BlendMode.ColorDodge
        com.hereliesaz.graffitixr.common.model.BlendMode.ColorBurn -> BlendMode.ColorBurn
        com.hereliesaz.graffitixr.common.model.BlendMode.Difference -> BlendMode.Difference
        com.hereliesaz.graffitixr.common.model.BlendMode.Exclusion -> BlendMode.Exclusion
        com.hereliesaz.graffitixr.common.model.BlendMode.Hue -> BlendMode.Hue
        com.hereliesaz.graffitixr.common.model.BlendMode.Saturation -> BlendMode.Saturation
        com.hereliesaz.graffitixr.common.model.BlendMode.Color -> BlendMode.Color
        com.hereliesaz.graffitixr.common.model.BlendMode.Luminosity -> BlendMode.Luminosity
        com.hereliesaz.graffitixr.common.model.BlendMode.Clear -> BlendMode.Clear
        com.hereliesaz.graffitixr.common.model.BlendMode.Src -> BlendMode.Src
        com.hereliesaz.graffitixr.common.model.BlendMode.Dst -> BlendMode.Dst
        com.hereliesaz.graffitixr.common.model.BlendMode.DstOver -> BlendMode.DstOver
        com.hereliesaz.graffitixr.common.model.BlendMode.SrcIn -> BlendMode.SrcIn
        com.hereliesaz.graffitixr.common.model.BlendMode.DstIn -> BlendMode.DstIn
        com.hereliesaz.graffitixr.common.model.BlendMode.SrcOut -> BlendMode.SrcOut
        com.hereliesaz.graffitixr.common.model.BlendMode.DstOut -> BlendMode.DstOut
        com.hereliesaz.graffitixr.common.model.BlendMode.SrcAtop -> BlendMode.SrcAtop
        com.hereliesaz.graffitixr.common.model.BlendMode.DstAtop -> BlendMode.DstAtop
        com.hereliesaz.graffitixr.common.model.BlendMode.Xor -> BlendMode.Xor
        com.hereliesaz.graffitixr.common.model.BlendMode.Plus -> BlendMode.Plus
        com.hereliesaz.graffitixr.common.model.BlendMode.Modulate -> BlendMode.Modulate
        com.hereliesaz.graffitixr.common.model.BlendMode.HardLight -> BlendMode.Hardlight
        com.hereliesaz.graffitixr.common.model.BlendMode.SoftLight -> BlendMode.Softlight
        else -> BlendMode.SrcOver
    }
}