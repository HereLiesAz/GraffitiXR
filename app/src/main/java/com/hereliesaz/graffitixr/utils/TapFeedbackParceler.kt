package com.hereliesaz.graffitixr.utils

import android.os.Parcel
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.TapFeedback
import kotlinx.parcelize.Parceler

object TapFeedbackParceler : Parceler<TapFeedback?> {
    override fun create(parcel: Parcel): TapFeedback? {
        return when (parcel.readInt()) {
            1 -> TapFeedback.Success(Offset(parcel.readFloat(), parcel.readFloat()))
            2 -> TapFeedback.Failure(Offset(parcel.readFloat(), parcel.readFloat()))
            else -> null
        }
    }

    override fun TapFeedback?.write(parcel: Parcel, flags: Int) {
        when (this) {
            is TapFeedback.Success -> {
                parcel.writeInt(1)
                parcel.writeFloat(position.x)
                parcel.writeFloat(position.y)
            }
            is TapFeedback.Failure -> {
                parcel.writeInt(2)
                parcel.writeFloat(position.x)
                parcel.writeFloat(position.y)
            }
            null -> parcel.writeInt(0)
        }
    }
}