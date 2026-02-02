package com.hereliesaz.graffitixr.common.model

import android.net.Uri
import android.os.Parcelable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import com.hereliesaz.graffitixr.common.serialization.BlendModeSerializer
import com.hereliesaz.graffitixr.common.serialization.OffsetSerializer
import com.hereliesaz.graffitixr.common.serialization.UriSerializer
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler
import kotlinx.serialization.Serializable
import java.util.UUID
import com.hereliesaz.graffitixr.common.OffsetParceler
import com.hereliesaz.graffitixr.common.BlendModeParceler

@Serializable
@Parcelize
data class OverlayLayer(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Layer",

    @Serializable(with = UriSerializer::class)
    val uri: Uri,

    val opacity: Float = 1.0f,
    val brightness: Float = 0.0f,
    val contrast: Float = 1.0f,
    val saturation: Float = 1.0f,
    val colorBalanceR: Float = 1.0f,
    val colorBalanceG: Float = 1.0f,
    val colorBalanceB: Float = 1.0f,
    val scale: Float = 1.0f,
    val rotationX: Float = 0.0f,
    val rotationY: Float = 0.0f,
    val rotationZ: Float = 0.0f,

    @Serializable(with = OffsetSerializer::class)
    @TypeParceler<Offset, OffsetParceler>
    val offset: Offset = Offset.Zero,

    @Serializable(with = BlendModeSerializer::class)
    @TypeParceler<BlendMode, BlendModeParceler>
    val blendMode: BlendMode = BlendMode.SrcOver,

    val isVisible: Boolean = true,
    val aspectRatio: Float = 1.0f 
) : Parcelable
