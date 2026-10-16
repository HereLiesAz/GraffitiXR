package com.hereliesaz.graffitixr.feature.ar

/**
 * Every artist-facing sentence the standalone SphereSLAM flow can show, as a typed key.
 *
 * The logic (failure classification, target quality gate, status pill, capture blockers) returns
 * these instead of English, so the wording lives only in `core/design` string resources (default
 * plus every `values-*` locale) and is resolved at the UI edge by [stringRes] (see
 * `StandaloneTextRes.kt`). Keeping this type free of Android resource ids lets the pure logic and its
 * JVM tests stay resource-free.
 */
internal enum class StandaloneTextKey {
    // One per SphereSLAM TrackingFailureReason (see StandaloneFailure.kt).
    FAILURE_NATIVE_LIBRARY_UNAVAILABLE,
    FAILURE_CAMERA_UNAVAILABLE,
    FAILURE_INTRINSICS_UNAVAILABLE,
    FAILURE_REFERENCE_TOO_WEAK,
    FAILURE_NO_PAGE_MATCH,
    FAILURE_STALE_OBSERVATION,
    FAILURE_REPROJECTION_ERROR,
    FAILURE_INSUFFICIENT_INLIERS,
    FAILURE_POSE_JUMP,
    FAILURE_PERSISTED_TARGET_CORRUPT,
    FAILURE_INTERNAL,

    /** Fatal analyzer with no more specific message. */
    TRACKING_STOPPED,

    // Top status pill while there is no usable lock.
    STATUS_MAPPING,
    STATUS_PREPARING,
    STATUS_FINDING,

    // Target quality gate: blocking issues, then warnings.
    QUALITY_TOO_SMALL,
    QUALITY_LOW_CONTRAST,
    QUALITY_BLURRY,
    QUALITY_NOT_STRONG_ENOUGH,
    QUALITY_TOO_DARK,
    QUALITY_TOO_BRIGHT,

    // Photosphere capture blockers.
    PHOTOSPHERE_NO_SENSOR,
    PHOTOSPHERE_COMPASS_NOT_READY,
    PHOTOSPHERE_NOT_MAPPED,

    // Target creation errors.
    CAPTURE_FAILED,
    RECTIFY_FAILED,

    /** Args: KPM feature count found, minimum required (both Int). */
    TARGET_TOO_WEAK,
}

/** A [StandaloneTextKey] plus the format arguments its string takes (empty for most). */
internal data class StandaloneText(
    val key: StandaloneTextKey,
    val args: List<Any> = emptyList(),
)
