package com.hereliesaz.graffitixr.data

import android.os.Parcelable
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.utils.OffsetListParceler
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith

@Parcelize
data class RefinementPath(
    val points: @WriteWith<OffsetListParceler> List<Offset>,
    val isEraser: Boolean
) : Parcelable