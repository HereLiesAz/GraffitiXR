package com.hereliesaz.graffitixr.data

import android.net.Uri
import android.os.Parcelable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import com.hereliesaz.graffitixr.RotationAxis
import com.hereliesaz.graffitixr.utils.BlendModeParceler
import com.hereliesaz.graffitixr.utils.OffsetListParceler
import com.hereliesaz.graffitixr.utils.OffsetParceler
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith

@Parcelize
data class OverlayLayer(
    val id: String,
    var name: String,
    val uri: Uri,
    val originalUri: Uri? = null,
    val backgroundRemovedUri: Uri? = null,

    // Transformations
    val scale: Float = 1f,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,
    val offset: @WriteWith<OffsetParceler> Offset = Offset.Zero,

    // Adjustments
    val opacity: Float = 1f,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val colorBalanceR: Float = 1f,
    val colorBalanceG: Float = 1f,
    val colorBalanceB: Float = 1f,
    val curvesPoints: @WriteWith<OffsetListParceler> List<Offset> = emptyList(),
    val blendMode: @WriteWith<BlendModeParceler> BlendMode = BlendMode.SrcOver,

    // State
    val isLocked: Boolean = false,
    val isVisible: Boolean = true
) : Parcelable
