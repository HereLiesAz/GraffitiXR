package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.graphics.BlendMode
import com.hereliesaz.graffitixr.common.model.BlendMode as DomainBlendMode

/**
 * Single source of truth for translating Domain BlendModes to Compose BlendModes.
 * Call this extension function directly on your domain model in Compose files.
 */
fun DomainBlendMode.toComposeBlendMode(): BlendMode {
    return when(this) {
        DomainBlendMode.Multiply -> BlendMode.Multiply
        DomainBlendMode.Screen -> BlendMode.Screen
        DomainBlendMode.Overlay -> BlendMode.Overlay
        DomainBlendMode.Darken -> BlendMode.Darken
        DomainBlendMode.Lighten -> BlendMode.Lighten
        DomainBlendMode.ColorDodge -> BlendMode.ColorDodge
        DomainBlendMode.ColorBurn -> BlendMode.ColorBurn
        DomainBlendMode.HardLight -> BlendMode.Hardlight
        DomainBlendMode.SoftLight -> BlendMode.Softlight
        DomainBlendMode.Difference -> BlendMode.Difference
        DomainBlendMode.Exclusion -> BlendMode.Exclusion
        DomainBlendMode.Hue -> BlendMode.Hue
        DomainBlendMode.Saturation -> BlendMode.Saturation
        DomainBlendMode.Color -> BlendMode.Color
        DomainBlendMode.Luminosity -> BlendMode.Luminosity
        DomainBlendMode.Clear -> BlendMode.Clear
        DomainBlendMode.Src -> BlendMode.Src
        DomainBlendMode.Dst -> BlendMode.Dst
        DomainBlendMode.DstOver -> BlendMode.DstOver
        DomainBlendMode.SrcIn -> BlendMode.SrcIn
        DomainBlendMode.DstIn -> BlendMode.DstIn
        DomainBlendMode.SrcOut -> BlendMode.SrcOut
        DomainBlendMode.DstOut -> BlendMode.DstOut
        DomainBlendMode.SrcAtop -> BlendMode.SrcAtop
        DomainBlendMode.DstAtop -> BlendMode.DstAtop
        DomainBlendMode.Xor -> BlendMode.Xor
        DomainBlendMode.Plus -> BlendMode.Plus
        DomainBlendMode.Modulate -> BlendMode.Modulate
        else -> BlendMode.SrcOver
    }
}