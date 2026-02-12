package com.hereliesaz.graffitixr.feature.editor

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.BlendMode as ModelBlendMode

@Composable
fun TraceScreen(
    uiState: EditorUiState,
    viewModel: EditorViewModel
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.White)) {

        uiState.layers.forEach { layer ->
            if (layer.isVisible) {
                Image(
                    bitmap = layer.bitmap.asImageBitmap(),
                    contentDescription = layer.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = layer.scale
                            scaleY = layer.scale
                            translationX = layer.offset.x
                            translationY = layer.offset.y
                            rotationX = layer.rotationX
                            rotationY = layer.rotationY
                            rotationZ = layer.rotationZ
                            alpha = layer.opacity
                        },
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                        Color.Transparent,
                        blendMode = mapBlendMode(layer.blendMode)
                    ),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

private fun mapBlendMode(mode: ModelBlendMode): BlendMode {
    return when(mode) {
        ModelBlendMode.SrcOver -> BlendMode.SrcOver
        ModelBlendMode.Multiply -> BlendMode.Multiply
        ModelBlendMode.Screen -> BlendMode.Screen
        ModelBlendMode.Overlay -> BlendMode.Overlay
        ModelBlendMode.Darken -> BlendMode.Darken
        ModelBlendMode.Lighten -> BlendMode.Lighten
        ModelBlendMode.ColorDodge -> BlendMode.ColorDodge
        ModelBlendMode.ColorBurn -> BlendMode.ColorBurn
        // Mapping HardLight/SoftLight to SrcOver (or Overlay) for safety
        ModelBlendMode.HardLight -> BlendMode.Overlay
        ModelBlendMode.SoftLight -> BlendMode.Overlay
        ModelBlendMode.Difference -> BlendMode.Difference
        ModelBlendMode.Exclusion -> BlendMode.Exclusion
        ModelBlendMode.Hue -> BlendMode.Hue
        ModelBlendMode.Saturation -> BlendMode.Saturation
        ModelBlendMode.Color -> BlendMode.Color
        ModelBlendMode.Luminosity -> BlendMode.Luminosity
        ModelBlendMode.Clear -> BlendMode.Clear
        ModelBlendMode.Src -> BlendMode.Src
        ModelBlendMode.Dst -> BlendMode.Dst
        ModelBlendMode.DstOver -> BlendMode.DstOver
        ModelBlendMode.SrcIn -> BlendMode.SrcIn
        ModelBlendMode.DstIn -> BlendMode.DstIn
        ModelBlendMode.SrcOut -> BlendMode.SrcOut
        ModelBlendMode.DstOut -> BlendMode.DstOut
        ModelBlendMode.SrcAtop -> BlendMode.SrcAtop
        ModelBlendMode.DstAtop -> BlendMode.DstAtop
        ModelBlendMode.Xor -> BlendMode.Xor
        ModelBlendMode.Plus -> BlendMode.Plus
        ModelBlendMode.Modulate -> BlendMode.Modulate
        else -> BlendMode.SrcOver
    }
}