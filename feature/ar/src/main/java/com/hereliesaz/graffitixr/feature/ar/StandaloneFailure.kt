package com.hereliesaz.graffitixr.feature.ar

internal enum class StandaloneFailureSeverity {
    TRANSIENT,
    RECOVERABLE,
    FATAL,
}

internal enum class StandaloneFailureReason {
    NATIVE_LIBRARY_UNAVAILABLE,
    CAMERA_UNAVAILABLE,
    INTRINSICS_UNAVAILABLE,
    REFERENCE_TOO_WEAK,
    NO_CURRENT_PAGE_MATCH,
    STALE_OBSERVATION,
    EXCESSIVE_REPROJECTION_ERROR,
    INSUFFICIENT_INLIERS,
    POSE_JUMP,
    PERSISTED_TARGET_CORRUPT,
    INTERNAL_FAILURE,
}

internal data class StandaloneFailureEvent(
    val reason: StandaloneFailureReason,
    val severity: StandaloneFailureSeverity,
    val userMessage: String,
    val diagnostic: String,
)

internal object StandaloneFailureClassifier {
    fun event(
        reason: StandaloneFailureReason,
        diagnostic: String = "",
    ): StandaloneFailureEvent {
        val severity = when (reason) {
            StandaloneFailureReason.NO_CURRENT_PAGE_MATCH,
            StandaloneFailureReason.STALE_OBSERVATION,
            StandaloneFailureReason.EXCESSIVE_REPROJECTION_ERROR,
            StandaloneFailureReason.INSUFFICIENT_INLIERS,
            StandaloneFailureReason.POSE_JUMP -> StandaloneFailureSeverity.TRANSIENT

            StandaloneFailureReason.REFERENCE_TOO_WEAK,
            StandaloneFailureReason.PERSISTED_TARGET_CORRUPT,
            StandaloneFailureReason.CAMERA_UNAVAILABLE,
            StandaloneFailureReason.INTRINSICS_UNAVAILABLE ->
                StandaloneFailureSeverity.RECOVERABLE

            StandaloneFailureReason.NATIVE_LIBRARY_UNAVAILABLE,
            StandaloneFailureReason.INTERNAL_FAILURE -> StandaloneFailureSeverity.FATAL
        }

        val message = when (reason) {
            StandaloneFailureReason.NATIVE_LIBRARY_UNAVAILABLE ->
                "SphereSLAM isn't available in this build."
            StandaloneFailureReason.CAMERA_UNAVAILABLE ->
                "Camera isn't available for standalone AR."
            StandaloneFailureReason.INTRINSICS_UNAVAILABLE ->
                "Camera calibration is unavailable for standalone AR."
            StandaloneFailureReason.REFERENCE_TOO_WEAK ->
                "That wall target has too little trackable detail. Capture a richer patch."
            StandaloneFailureReason.NO_CURRENT_PAGE_MATCH ->
                "Keep the wall target in view."
            StandaloneFailureReason.STALE_OBSERVATION ->
                "Tracking is lagging. Move the phone more slowly."
            StandaloneFailureReason.EXCESSIVE_REPROJECTION_ERROR ->
                "Wall match is unstable. Reframe the target."
            StandaloneFailureReason.INSUFFICIENT_INLIERS ->
                "Not enough target detail is visible."
            StandaloneFailureReason.POSE_JUMP ->
                "Tracking jump rejected. Hold on the wall target."
            StandaloneFailureReason.PERSISTED_TARGET_CORRUPT ->
                "Saved wall target is missing or unreadable. Capture a new target."
            StandaloneFailureReason.INTERNAL_FAILURE ->
                "SphereSLAM couldn't continue tracking this target."
        }

        return StandaloneFailureEvent(
            reason = reason,
            severity = severity,
            userMessage = message,
            diagnostic = diagnostic,
        )
    }

    fun fromPoseRejection(
        rejection: StandalonePoseRejection,
        diagnostic: String,
    ): StandaloneFailureEvent = event(
        reason = when (rejection) {
            StandalonePoseRejection.TOO_FEW_INLIERS ->
                StandaloneFailureReason.INSUFFICIENT_INLIERS
            StandalonePoseRejection.EXCESSIVE_REPROJECTION_ERROR ->
                StandaloneFailureReason.EXCESSIVE_REPROJECTION_ERROR
            StandalonePoseRejection.TRANSLATION_JUMP,
            StandalonePoseRejection.ANGULAR_JUMP ->
                StandaloneFailureReason.POSE_JUMP
            StandalonePoseRejection.NON_FINITE ->
                StandaloneFailureReason.INTERNAL_FAILURE
        },
        diagnostic = diagnostic,
    )

    fun fromThrowable(error: Throwable): StandaloneFailureEvent = when (error) {
        is UnsatisfiedLinkError -> event(
            StandaloneFailureReason.NATIVE_LIBRARY_UNAVAILABLE,
            "nativeError=${error::class.java.simpleName}: ${error.message.orEmpty()}",
        )
        is StandaloneReferenceTooWeakException -> event(
            StandaloneFailureReason.REFERENCE_TOO_WEAK,
            "features=${error.featureCount} minimum=${error.minimumFeatureCount}",
        )
        else -> event(
            StandaloneFailureReason.INTERNAL_FAILURE,
            "error=${error::class.java.simpleName}: ${error.message.orEmpty()}",
        )
    }
}
