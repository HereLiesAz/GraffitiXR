package com.hereliesaz.graffitixr

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

<<<<<<< HEAD
@Parcelize
enum class EditorMode : Parcelable {
    STATIC,
    OVERLAY,
    TRACE,
    AR
=======
@Serializable
enum class EditorMode {
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
>>>>>>> origin/feature/ar-editor-enhancements-4573859779138866612
}
