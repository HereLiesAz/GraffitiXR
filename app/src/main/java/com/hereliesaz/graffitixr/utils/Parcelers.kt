package com.hereliesaz.graffitixr.utils

import android.os.Parcel
import androidx.compose.ui.geometry.Offset
import kotlinx.parcelize.Parceler

/**
 * A custom [Parceler] for the [Offset] class.
 *
 * The `Offset` class from Jetpack Compose is not inherently `Parcelable`. This object
 * provides the logic to manually write the `x` and `y` coordinates to a [Parcel]
 * and create an `Offset` instance from a `Parcel`, allowing it to be used within
 * `@Parcelize`-annotated data classes.
 */
object OffsetParceler : Parceler<Offset> {
    override fun create(parcel: Parcel): Offset {
        return Offset(parcel.readFloat(), parcel.readFloat())
    }

    override fun Offset.write(parcel: Parcel, flags: Int) {
        parcel.writeFloat(x)
        parcel.writeFloat(y)
    }
}