package com.hereliesaz.graffitixr.common.model

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import kotlinx.serialization.Serializable
import com.hereliesaz.graffitixr.common.serialization.UriSerializer
import com.hereliesaz.graffitixr.common.serialization.OffsetSerializer
import java.util.UUID

@Serializable
data class OverlayLayer(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Layer",
    @Serializable(with = UriSerializer::class)
    val uri: Uri,
    val scale: Float = 1.0f,
    @Serializable(with = OffsetSerializer::class)
    val offset: Offset = Offset.Zero,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,
    val opacity: Float = 1.0f,
    val blendMode: BlendMode = BlendMode.SrcOver,
    val brightness: Float = 0f,
    val contrast: Float = 1.0f,
    val saturation: Float = 1.0f,
    val colorBalanceR: Float = 0f,
    val colorBalanceG: Float = 0f,
    val colorBalanceB: Float = 0f,
    val isImageLocked: Boolean = false,
    val isVisible: Boolean = true,
    val warpMesh: List<Float>? = null,
    val isSketch: Boolean = false
)