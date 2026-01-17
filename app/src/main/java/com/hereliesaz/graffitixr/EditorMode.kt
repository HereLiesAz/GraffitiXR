package com.hereliesaz.graffitixr

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Serializable
@Parcelize
enum class EditorMode : Parcelable {
    AR,      // Standard AR placement/viewing
    CROP,    // Cropping the overlay image
    ADJUST,  // Adjusting color/brightness/contrast
    DRAW,    // Drawing masks or lines
    PROJECT, // Project management or export
    STATIC,  // Non-AR static mockup
    OVERLAY, // Camera overlay mode
    TRACE    // Trace mode
}
