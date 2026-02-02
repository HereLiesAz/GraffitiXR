package com.hereliesaz.graffitixr.common.model

import android.os.Parcelable
import androidx.compose.ui.geometry.Offset
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import kotlinx.serialization.Serializable
import com.hereliesaz.graffitixr.common.serialization.RefinementPathSerializer
import com.hereliesaz.graffitixr.common.OffsetListParceler

@Serializable(with = RefinementPathSerializer::class)
@Parcelize
data class RefinementPath(
    val points: @WriteWith<OffsetListParceler> List<Offset>,
    val isEraser: Boolean
) : Parcelable