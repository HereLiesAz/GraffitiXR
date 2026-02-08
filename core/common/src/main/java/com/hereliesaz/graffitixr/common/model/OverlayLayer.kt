package com.hereliesaz.graffitixr.common.model

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import kotlinx.serialization.Serializable
import com.hereliesaz.graffitixr.common.serialization.UriSerializer
import com.hereliesaz.graffitixr.common.serialization.OffsetSerializer
import com.hereliesaz.graffitixr.common.serialization.BlendModeSerializer
import java.util.UUID

@Serializable
data class OverlayLayer(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Layer",

    @Serializable(with = UriSerializer::class)
    val uri: Uri,

    // Transforms
    val scale: Float = 1.0f,
    @Serializable(with = OffsetSerializer::class)
    val offset: Offset = Offset.Zero,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,

    // Visuals
    val opacity: Float = 1.0f,
    @Serializable(with = BlendModeSerializer::class)
    val blendMode: BlendMode = BlendMode.SrcOver,

    // Color Correction
    val brightness: Float = 0f,
    val contrast: Float = 1.0f,
    val saturation: Float = 1.0f,
    val colorBalanceR: Float = 1.0f, // Changed default to 1.0 based on EditorViewModel usage (multiply)
    val colorBalanceG: Float = 1.0f,
    val colorBalanceB: Float = 1.0f,

    val isImageLocked: Boolean = false, // Renamed from isLocked to match EditorViewModel
    val isVisible: Boolean = true
)
