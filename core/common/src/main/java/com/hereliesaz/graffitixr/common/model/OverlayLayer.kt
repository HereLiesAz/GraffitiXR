package com.hereliesaz.graffitixr.common.model

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import kotlinx.serialization.Serializable
import com.hereliesaz.graffitixr.common.util.UriSerializer
import com.hereliesaz.graffitixr.common.util.OffsetSerializer
import java.util.UUID

@Serializable
data class OverlayLayer(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Layer",

    @Serializable(with = UriSerializer::class)
    val uri: Uri,

    // Transforms
    @Serializable(with = OffsetSerializer::class)
    val offset: Offset = Offset.Zero,
    val scale: Float = 1.0f,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,

    // Visuals
    val opacity: Float = 1.0f,
    val blendMode: String = "SrcOver",

    // Color Correction
    val brightness: Float = 0f, // -1.0 to 1.0
    val contrast: Float = 1.0f, // 0.0 to 2.0
    val saturation: Float = 1.0f, // 0.0 to 2.0
    val colorBalanceR: Float = 0f,
    val colorBalanceG: Float = 0f,
    val colorBalanceB: Float = 0f,

    val isLocked: Boolean = false,
    val isVisible: Boolean = true
)