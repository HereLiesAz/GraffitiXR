package com.hereliesaz.graffitixr.data

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.data.UriSerializer
import kotlinx.serialization.Serializable

@Serializable
data class ProjectData(
    @Serializable(with = UriSerializer::class)
    val backgroundImageUri: Uri?,
    @Serializable(with = UriSerializer::class)
    val overlayImageUri: Uri?,
    @Serializable(with = UriSerializer::class)
    val targetImageUri: Uri? = null,
    val opacity: Float,
    val contrast: Float,
    val saturation: Float,
    val colorBalanceR: Float,
    val colorBalanceG: Float,
    val colorBalanceB: Float,
    val scale: Float,
    val rotationZ: Float,
    val rotationX: Float,
    val rotationY: Float,
    @Serializable(with = OffsetSerializer::class)
    val offset: Offset,
    @Serializable(with = BlendModeSerializer::class)
    val blendMode: androidx.compose.ui.graphics.BlendMode,
    val fingerprintJson: String?
)
