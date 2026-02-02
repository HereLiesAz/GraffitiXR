package com.hereliesaz.graffitixr.common

import android.os.Parcel
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
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

object DrawingPathsParceler : Parceler<List<List<Pair<Float, Float>>>> {
    override fun create(parcel: Parcel): List<List<Pair<Float, Float>>> {
        val pathCount = parcel.readInt()
        return List(pathCount) {
            val pointCount = parcel.readInt()
            List(pointCount) {
                Pair(parcel.readFloat(), parcel.readFloat())
            }
        }
    }

    override fun List<List<Pair<Float, Float>>>.write(parcel: Parcel, flags: Int) {
        parcel.writeInt(size)
        forEach { path ->
            parcel.writeInt(path.size)
            path.forEach { point ->
                parcel.writeFloat(point.first)
                parcel.writeFloat(point.second)
            }
        }
    }
}

object BlendModeParceler : Parceler<BlendMode> {
    override fun create(parcel: Parcel): BlendMode {
        return when (parcel.readInt()) {
            1 -> BlendMode.Multiply
            2 -> BlendMode.Screen
            3 -> BlendMode.Overlay
            4 -> BlendMode.Darken
            5 -> BlendMode.Lighten
            else -> BlendMode.SrcOver
        }
    }

    override fun BlendMode.write(parcel: Parcel, flags: Int) {
        parcel.writeInt(
            when (this) {
                BlendMode.Multiply -> 1
                BlendMode.Screen -> 2
                BlendMode.Overlay -> 3
                BlendMode.Darken -> 4
                BlendMode.Lighten -> 5
                else -> 0
            }
        )
    }
}

object OffsetListParceler : Parceler<List<Offset>> {
    override fun create(parcel: Parcel): List<Offset> {
        val size = parcel.readInt()
        return List(size) { OffsetParceler.create(parcel) }
    }

    override fun List<Offset>.write(parcel: Parcel, flags: Int) {
        parcel.writeInt(size)
        forEach { with(OffsetParceler) { it.write(parcel, flags) } }
    }
}
