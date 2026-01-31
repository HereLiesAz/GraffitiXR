package com.hereliesaz.graffitixr.domain.model

enum class CaptureStep {
    PREVIEW,
    CAPTURE,
    CONFIRM,
    PROCESSING,
    COMPLETED,
    FAILED,

    // Guided Steps
    CHOOSE_METHOD,
    GUIDED_GRID,
    GUIDED_POINTS,
    GUIDED_CAPTURE,
    INSTRUCTION,
    ADVICE,
    GRID_CONFIG,
    PHOTO_SEQUENCE,

    // Directions / Specific Actions
    FRONT,
    LEFT,
    RIGHT,
    UP,
    DOWN,

    // Rectification & Calibration
    RECTIFY,
    MULTI_POINT_CALIBRATION,
    CALIBRATION_POINT_1,
    CALIBRATION_POINT_2,
    CALIBRATION_POINT_3,
    CALIBRATION_POINT_4,

    // Other
    ASK_GPS,
    REVIEW
}

enum class TargetCreationMode {
    SINGLE_IMAGE,
    MULTI_POINT,

    // New modes used in Overlay
    CAPTURE,
    RECTIFY,
    GUIDED_GRID,
    GUIDED_POINTS,
    MULTI_POINT_CALIBRATION
}
