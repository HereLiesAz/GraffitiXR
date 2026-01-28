package com.hereliesaz.graffitixr

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

object BlendModeParceler : Parceler<BlendMode> {
    override fun create(parcel: Parcel): BlendMode {
        val intValue = parcel.readInt()
        return when (intValue) {
            0 -> BlendMode.Clear
            1 -> BlendMode.Src
            2 -> BlendMode.Dst
            3 -> BlendMode.SrcOver
            4 -> BlendMode.DstOver
            5 -> BlendMode.SrcIn
            6 -> BlendMode.DstIn
            7 -> BlendMode.SrcOut
            8 -> BlendMode.DstOut
            9 -> BlendMode.SrcAtop
            10 -> BlendMode.DstAtop
            11 -> BlendMode.Xor
            12 -> BlendMode.Plus
            13 -> BlendMode.Modulate
            14 -> BlendMode.Screen
            15 -> BlendMode.Overlay
            16 -> BlendMode.Darken
            17 -> BlendMode.Lighten
            18 -> BlendMode.ColorDodge
            19 -> BlendMode.ColorBurn
            20 -> BlendMode.Hardlight
            21 -> BlendMode.Softlight
            22 -> BlendMode.Difference
            23 -> BlendMode.Exclusion
            24 -> BlendMode.Multiply
            25 -> BlendMode.Hue
            26 -> BlendMode.Saturation
            27 -> BlendMode.Color
            28 -> BlendMode.Luminosity
            else -> BlendMode.SrcOver
        }
    }

    override fun BlendMode.write(parcel: Parcel, flags: Int) {
        val intValue = when (this) {
            BlendMode.Clear -> 0
            BlendMode.Src -> 1
            BlendMode.Dst -> 2
            BlendMode.SrcOver -> 3
            BlendMode.DstOver -> 4
            BlendMode.SrcIn -> 5
            BlendMode.DstIn -> 6
            BlendMode.SrcOut -> 7
            BlendMode.DstOut -> 8
            BlendMode.SrcAtop -> 9
            BlendMode.DstAtop -> 10
            BlendMode.Xor -> 11
            BlendMode.Plus -> 12
            BlendMode.Modulate -> 13
            BlendMode.Screen -> 14
            BlendMode.Overlay -> 15
            BlendMode.Darken -> 16
            BlendMode.Lighten -> 17
            BlendMode.ColorDodge -> 18
            BlendMode.ColorBurn -> 19
            BlendMode.Hardlight -> 20
            BlendMode.Softlight -> 21
            BlendMode.Difference -> 22
            BlendMode.Exclusion -> 23
            BlendMode.Multiply -> 24
            BlendMode.Hue -> 25
            BlendMode.Saturation -> 26
            BlendMode.Color -> 27
            BlendMode.Luminosity -> 28
            else -> 3
        }
        parcel.writeInt(intValue)
    }
}

object TapFeedbackParceler : Parceler<TapFeedback?> {
    override fun create(parcel: Parcel): TapFeedback? {
        val isNull = parcel.readInt() == 1
        if (isNull) return null

        val type = parcel.readInt()
        val position = OffsetParceler.create(parcel)
        return when (type) {
            0 -> TapFeedback.Success(position)
            1 -> TapFeedback.Failure(position)
            else -> throw IllegalArgumentException("Invalid type for TapFeedback")
        }
    }

    override fun TapFeedback?.write(parcel: Parcel, flags: Int) {
        if (this == null) {
            parcel.writeInt(1) // Mark as null
            return
        }
        parcel.writeInt(0) // Mark as not null
        when (this) {
            is TapFeedback.Success -> {
                parcel.writeInt(0)
                with(OffsetParceler) { position.write(parcel, flags) }
            }
            is TapFeedback.Failure -> {
                parcel.writeInt(1)
                with(OffsetParceler) { position.write(parcel, flags) }
            }
        }
    }
}

object DrawingPathsParceler : Parceler<List<List<Pair<Float, Float>>>> {
    override fun create(parcel: Parcel): List<List<Pair<Float, Float>>> {
        val outerListSize = parcel.readInt()
        val outerList = ArrayList<List<Pair<Float, Float>>>(outerListSize)
        repeat(outerListSize) {
            val innerListSize = parcel.readInt()
            val innerList = ArrayList<Pair<Float, Float>>(innerListSize)
            repeat(innerListSize) {
                innerList.add(Pair(parcel.readFloat(), parcel.readFloat()))
            }
            outerList.add(innerList)
        }
        return outerList
    }

    override fun List<List<Pair<Float, Float>>>.write(parcel: Parcel, flags: Int) {
        parcel.writeInt(size)
        forEach { innerList ->
            parcel.writeInt(innerList.size)
            innerList.forEach { pair ->
                parcel.writeFloat(pair.first)
                parcel.writeFloat(pair.second)
            }
        }
    }
}
