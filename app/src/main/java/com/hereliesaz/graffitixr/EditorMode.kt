package com.hereliesaz.graffitixr

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
enum class EditorMode : Parcelable {
    STATIC,
    GHOST,
    TRACE,
    AR,
    HELP
}
