package com.hereliesaz.graffitixr.data

import android.net.Uri
import com.hereliesaz.graffitixr.data.UriSerializer
import kotlinx.serialization.Serializable

@Serializable
data class ProjectData(
    @Serializable(with = UriSerializer::class)
    val backgroundImageUri: Uri?,
    @Serializable(with = UriSerializer::class)
    val overlayImageUri: Uri?,
    val opacity: Float,
    val contrast: Float,
    val saturation: Float,
    val colorBalanceR: Float,
    val colorBalanceG: Float,
    val colorBalanceB: Float
)
