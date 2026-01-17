package com.hereliesaz.graffitixr.data

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import java.util.UUID

data class OverlayLayer(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val name: String,
    val isVisible: Boolean = true,

    // Transforms
    val scale: Float = 1.0f,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,
    val offset: Offset = Offset.Zero,

    // Adjustments (Added to fix Unresolved References)
    val opacity: Float = 1.0f,
    val brightness: Float = 0f,
    val contrast: Float = 1.0f,
    val saturation: Float = 1.0f,

    // Color Balance (Added to fix Unresolved References)
    val colorBalanceR: Float = 1.0f,
    val colorBalanceG: Float = 1.0f,
    val colorBalanceB: Float = 1.0f,

    // Composition
    val blendMode: BlendMode = BlendMode.SrcOver
)