package com.hereliesaz.graffitixr

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Parcelize
@Serializable
enum class EditorMode : Parcelable {
    STATIC,  // Static background mockup
    AR,      // Standard AR placement/viewing
    OVERLAY, // Camera overlay
    TRACE,   // Lightbox tracing
    CROP,    // Cropping the overlay image
    ADJUST,  // Adjusting color/brightness/contrast
    DRAW,    // Drawing masks or lines
    PROJECT, // Project management or export
    ISOLATE, // Background removal
    BALANCE, // Color balance
    OUTLINE  // Edge detection
}
