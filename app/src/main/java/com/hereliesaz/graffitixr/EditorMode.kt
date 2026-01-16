package com.hereliesaz.graffitixr

import kotlinx.serialization.Serializable

@Serializable
enum class EditorMode {
    STATIC,  // Static background mockup
    AR,      // Standard AR placement/viewing
    OVERLAY, // Camera overlay
    TRACE,   // Lightbox tracing
    CROP,    // Cropping the overlay image
    ADJUST,  // Adjusting color/brightness/contrast
    DRAW,    // Drawing masks or lines
    PROJECT  // Project management or export
}
