package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
import com.hereliesaz.graffitixr.common.model.Layer

@Composable
fun OverlayScreen(layer: Layer) {
    if (layer.isVisible) {
        layer.bitmap?.let { b ->
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                alpha = layer.opacity
                // BlendMode handled via GraphicsLayer or Canvas
            )
        }
    }
}