@file:OptIn(com.hereliesaz.sphereslam.reloc.ExperimentalSphereSlamRelocApi::class)

package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.sphereslam.reloc.TrackingFailureEvent
import com.hereliesaz.sphereslam.reloc.TrackingFailureReason

/*
 * The standalone failure taxonomy is SphereSLAM's (`reloc.TrackingFailure*`, ported from this file):
 * reasons, severities and the classifier that maps pose rejections and exceptions onto them. The
 * library deliberately carries no user-facing wording, so the app's on-screen wording is a typed [StandaloneTextKey] here
 * ([userText]), localized in `core/design` string resources.
 */
internal typealias StandaloneFailureSeverity = com.hereliesaz.sphereslam.reloc.TrackingFailureSeverity
internal typealias StandaloneFailureReason = TrackingFailureReason
internal typealias StandaloneFailureEvent = TrackingFailureEvent
internal typealias StandaloneFailureClassifier = com.hereliesaz.sphereslam.reloc.TrackingFailureClassifier

/** The typed (localizable) artist-facing explanation key for this failure reason. */
internal fun TrackingFailureReason.textKey(): StandaloneTextKey = when (this) {
    TrackingFailureReason.NATIVE_LIBRARY_UNAVAILABLE -> StandaloneTextKey.FAILURE_NATIVE_LIBRARY_UNAVAILABLE
    TrackingFailureReason.CAMERA_UNAVAILABLE -> StandaloneTextKey.FAILURE_CAMERA_UNAVAILABLE
    TrackingFailureReason.INTRINSICS_UNAVAILABLE -> StandaloneTextKey.FAILURE_INTRINSICS_UNAVAILABLE
    TrackingFailureReason.REFERENCE_TOO_WEAK -> StandaloneTextKey.FAILURE_REFERENCE_TOO_WEAK
    TrackingFailureReason.NO_CURRENT_PAGE_MATCH -> StandaloneTextKey.FAILURE_NO_PAGE_MATCH
    TrackingFailureReason.STALE_OBSERVATION -> StandaloneTextKey.FAILURE_STALE_OBSERVATION
    TrackingFailureReason.EXCESSIVE_REPROJECTION_ERROR -> StandaloneTextKey.FAILURE_REPROJECTION_ERROR
    TrackingFailureReason.INSUFFICIENT_INLIERS -> StandaloneTextKey.FAILURE_INSUFFICIENT_INLIERS
    TrackingFailureReason.POSE_JUMP -> StandaloneTextKey.FAILURE_POSE_JUMP
    TrackingFailureReason.PERSISTED_TARGET_CORRUPT -> StandaloneTextKey.FAILURE_PERSISTED_TARGET_CORRUPT
    TrackingFailureReason.INTERNAL_FAILURE -> StandaloneTextKey.FAILURE_INTERNAL
}

/** The artist-facing explanation of this failure, as a typed key resolved at the UI edge. */
internal val TrackingFailureEvent.userText: StandaloneText
    get() = StandaloneText(reason.textKey())
