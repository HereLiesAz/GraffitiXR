package com.hereliesaz.graffitixr.design

import android.os.Parcelable
import androidx.compose.ui.geometry.Offset
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith

@Parcelize
sealed class TapFeedback : Parcelable {
    @Parcelize
    data class Success(val position: @WriteWith<OffsetParceler> Offset) : TapFeedback()
    @Parcelize
    data class Failure(val position: @WriteWith<OffsetParceler> Offset) : TapFeedback()
}
