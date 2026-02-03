package com.hereliesaz.graffitixr.common.model

import android.os.Parcel
import android.os.Parcelable
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.OffsetParceler
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith

@Parcelize
sealed class TapFeedback : Parcelable {
    @Parcelize
    data class Success(val position: @WriteWith<OffsetParceler> Offset) : TapFeedback()
    @Parcelize
    data class Failure(val position: @WriteWith<OffsetParceler> Offset) : TapFeedback()
}

object TapFeedbackParceler : Parceler<TapFeedback?> {
    override fun create(parcel: Parcel): TapFeedback? {
        val exists = parcel.readByte() != 0.toByte()
        if (!exists) return null

        val type = parcel.readInt()
        val x = parcel.readFloat()
        val y = parcel.readFloat()
        return when (type) {
            0 -> TapFeedback.Success(Offset(x, y))
            else -> TapFeedback.Failure(Offset(x, y))
        }
    }

    override fun TapFeedback?.write(parcel: Parcel, flags: Int) {
        if (this == null) {
            parcel.writeByte(0)
            return
        }
        parcel.writeByte(1)
        when (this) {
            is TapFeedback.Success -> {
                parcel.writeInt(0)
                parcel.writeFloat(position.x)
                parcel.writeFloat(position.y)
            }
            is TapFeedback.Failure -> {
                parcel.writeInt(1)
                parcel.writeFloat(position.x)
                parcel.writeFloat(position.y)
            }
        }
    }
}
