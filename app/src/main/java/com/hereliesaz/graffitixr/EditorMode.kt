package com.hereliesaz.graffitixr

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
enum class EditorMode : Parcelable {
    STATIC,
    OVERLAY,
    TRACE,
    AR,
    HELP // Shows the Help/Onboarding screen
}
