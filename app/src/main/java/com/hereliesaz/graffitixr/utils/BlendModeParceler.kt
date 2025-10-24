package com.hereliesaz.graffitixr.utils

import android.os.Parcel
import androidx.compose.ui.graphics.BlendMode
import kotlinx.parcelize.Parceler

object BlendModeParceler : Parceler<BlendMode> {
    override fun create(parcel: Parcel): BlendMode {
        return when (parcel.readString()) {
            "SrcOver" -> BlendMode.SrcOver
            "Multiply" -> BlendMode.Multiply
            "Screen" -> BlendMode.Screen
            "Overlay" -> BlendMode.Overlay
            "Darken" -> BlendMode.Darken
            "Lighten" -> BlendMode.Lighten
            else -> BlendMode.SrcOver
        }
    }

    override fun BlendMode.write(parcel: Parcel, flags: Int) {
        parcel.writeString(this.toString())
    }
}
