package com.hereliesaz.graffitixr.common

import android.os.Parcel
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import com.hereliesaz.graffitixr.feature.ar.*
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
