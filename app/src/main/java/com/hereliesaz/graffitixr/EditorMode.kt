package com.hereliesaz.graffitixr

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
enum class EditorMode : Parcelable {
    STATIC,
    NON_AR,
    AR,
    HELP
}
