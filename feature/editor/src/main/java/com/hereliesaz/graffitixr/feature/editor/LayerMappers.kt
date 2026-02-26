package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.OverlayLayer
import com.hereliesaz.graffitixr.common.model.BlendMode as ModelBlendMode
import androidx.compose.ui.graphics.BlendMode as ComposeBlendMode

fun Layer.toOverlayLayer(): OverlayLayer {
    return OverlayLayer(
        id = id,
        name = name,
        uri = uri ?: android.net.Uri.EMPTY,
        scale = scale,
        offset = offset,
        rotationX = rotationX,
        rotationY = rotationY,
        rotationZ = rotationZ,
        opacity = opacity,
        blendMode = blendMode.toModelBlendMode(),
        brightness = brightness,
        contrast = contrast,
        saturation = saturation,
        colorBalanceR = colorBalanceR,
        colorBalanceG = colorBalanceG,
        colorBalanceB = colorBalanceB,
        isImageLocked = isImageLocked,
        isVisible = isVisible,
        warpMesh = warpMesh,
        isSketch = isSketch
    )
}

fun OverlayLayer.toLayer(): Layer {
    return Layer(
        id = id,
        name = name,
        uri = if (uri == android.net.Uri.EMPTY) null else uri,
        scale = scale,
        offset = offset,
        rotationX = rotationX,
        rotationY = rotationY,
        rotationZ = rotationZ,
        opacity = opacity,
        blendMode = blendMode.toComposeBlendMode(),
        brightness = brightness,
        contrast = contrast,
        saturation = saturation,
        colorBalanceR = colorBalanceR,
        colorBalanceG = colorBalanceG,
        colorBalanceB = colorBalanceB,
        isImageLocked = isImageLocked,
        isVisible = isVisible,
        warpMesh = warpMesh ?: emptyList(),
        isSketch = isSketch
    )
}

fun ComposeBlendMode.toModelBlendMode(): ModelBlendMode {
    return when (this) {
        ComposeBlendMode.SrcOver -> ModelBlendMode.SrcOver
        ComposeBlendMode.Multiply -> ModelBlendMode.Multiply
        ComposeBlendMode.Screen -> ModelBlendMode.Screen
        ComposeBlendMode.Overlay -> ModelBlendMode.Overlay
        ComposeBlendMode.Darken -> ModelBlendMode.Darken
        ComposeBlendMode.Lighten -> ModelBlendMode.Lighten
        ComposeBlendMode.ColorDodge -> ModelBlendMode.ColorDodge
        ComposeBlendMode.ColorBurn -> ModelBlendMode.ColorBurn
        ComposeBlendMode.Hardlight -> ModelBlendMode.HardLight
        ComposeBlendMode.Softlight -> ModelBlendMode.SoftLight
        ComposeBlendMode.Difference -> ModelBlendMode.Difference
        ComposeBlendMode.Exclusion -> ModelBlendMode.Exclusion
        ComposeBlendMode.Hue -> ModelBlendMode.Hue
        ComposeBlendMode.Saturation -> ModelBlendMode.Saturation
        ComposeBlendMode.Color -> ModelBlendMode.Color
        ComposeBlendMode.Luminosity -> ModelBlendMode.Luminosity
        ComposeBlendMode.Clear -> ModelBlendMode.Clear
        ComposeBlendMode.Src -> ModelBlendMode.Src
        ComposeBlendMode.Dst -> ModelBlendMode.Dst
        ComposeBlendMode.DstOver -> ModelBlendMode.DstOver
        ComposeBlendMode.SrcIn -> ModelBlendMode.SrcIn
        ComposeBlendMode.DstIn -> ModelBlendMode.DstIn
        ComposeBlendMode.SrcOut -> ModelBlendMode.SrcOut
        ComposeBlendMode.DstOut -> ModelBlendMode.DstOut
        ComposeBlendMode.SrcAtop -> ModelBlendMode.SrcAtop
        ComposeBlendMode.DstAtop -> ModelBlendMode.DstAtop
        ComposeBlendMode.Xor -> ModelBlendMode.Xor
        ComposeBlendMode.Plus -> ModelBlendMode.Plus
        ComposeBlendMode.Modulate -> ModelBlendMode.Modulate
        else -> ModelBlendMode.SrcOver
    }
}
