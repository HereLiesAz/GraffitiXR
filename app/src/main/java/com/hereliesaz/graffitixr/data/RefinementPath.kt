package com.hereliesaz.graffitixr.data

import android.os.Parcelable
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.feature.ar.*
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import kotlinx.serialization.Serializable

@Serializable(with = RefinementPathSerializer::class)
@Parcelize
data class RefinementPath(
    val points: @WriteWith<OffsetListParceler> List<Offset>,
    val isEraser: Boolean
) : Parcelable