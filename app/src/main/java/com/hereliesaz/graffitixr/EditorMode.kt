package com.hereliesaz.graffitixr

import kotlinx.serialization.Serializable

@Serializable
enum class EditorMode {
    AR,      // Standard AR placement/viewing
    CROP,    // Cropping the overlay image
    ADJUST,  // Adjusting color/brightness/contrast
    DRAW,    // Drawing masks or lines
    PROJECT  // Project management or export
}

@Serializable
enum class RotationAxis {
    X, Y, Z
}

// Enum for target creation workflow
enum class TargetCreationMode {
    CAPTURE,
    GUIDED_GRID,
    GUIDED_POINTS,
    MULTI_POINT_CALIBRATION,
    RECTIFY
}

enum class CaptureStep {
    ADVICE,
    CHOOSE_METHOD,
    FRONT,
    LEFT,
    RIGHT,
    UP,
    DOWN,
    REVIEW,
    INSTRUCTION,
    GRID_CONFIG,
    ASK_GPS,
    CALIBRATION_POINT_1,
    CALIBRATION_POINT_2,
    CALIBRATION_POINT_3,
    CALIBRATION_POINT_4,
    GUIDED_CAPTURE,
    PHOTO_SEQUENCE,
    RECTIFY
}

enum class TargetCreationState {
    IDLE,
    SUCCESS,
    FAILURE
}

enum class ArState {
    SEARCHING,
    LOCKED,
    PLACED
}

// Data class for tap feedback
sealed class TapFeedback(val position: androidx.compose.ui.geometry.Offset) {
    class Success(position: androidx.compose.ui.geometry.Offset) : TapFeedback(position)
    class Failure(position: androidx.compose.ui.geometry.Offset) : TapFeedback(position)
}