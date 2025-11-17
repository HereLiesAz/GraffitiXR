package com.hereliesaz.graffitixr.utils

import android.os.Parcel
import androidx.compose.ui.geometry.Offset
import kotlinx.parcelize.Parceler

object OffsetParceler : Parceler<Offset> {
    override fun create(parcel: Parcel): Offset {
        return Offset(parcel.readFloat(), parcel.readFloat())
    }

    override fun Offset.write(parcel: Parcel, flags: Int) {
        parcel.writeFloat(x)
        parcel.writeFloat(y)
    }
}

object OffsetListParceler : Parceler<List<Offset>> {
    override fun create(parcel: Parcel): List<Offset> {
        val size = parcel.readInt()
        val list = mutableListOf<Offset>()
        repeat(size) {
            list.add(OffsetParceler.create(parcel))
        }
        return list
    }

    override fun List<Offset>.write(parcel: Parcel, flags: Int) {
        parcel.writeInt(size)
        forEach {
            with(OffsetParceler) {
                it.write(parcel, flags)
            }
        }
    }
}