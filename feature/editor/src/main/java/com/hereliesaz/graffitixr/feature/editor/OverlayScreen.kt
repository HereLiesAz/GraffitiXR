package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import com.hereliesaz.graffitixr.common.model.Layer

@Composable
fun OverlayScreen(layer: Layer) {
    if (layer.isVisible) {
        layer.bitmap?.let { b ->
            // Create ColorMatrix for image adjustments
            val colorMatrix = remember(
                layer.saturation,
                layer.contrast,
                layer.brightness,
                layer.colorBalanceR,
                layer.colorBalanceG,
                layer.colorBalanceB
            ) {
                createColorMatrix(
                    saturation = layer.saturation,
                    contrast = layer.contrast,
                    brightness = layer.brightness,
                    colorBalanceR = layer.colorBalanceR,
                    colorBalanceG = layer.colorBalanceG,
                    colorBalanceB = layer.colorBalanceB
                )
            }

            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                alpha = layer.opacity,
                colorFilter = ColorFilter.colorMatrix(colorMatrix)
                // BlendMode handled via GraphicsLayer or Canvas
            )
        }
    }
}
