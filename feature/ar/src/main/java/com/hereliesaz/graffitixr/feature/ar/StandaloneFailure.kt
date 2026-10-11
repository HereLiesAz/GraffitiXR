@file:OptIn(com.hereliesaz.sphereslam.reloc.ExperimentalSphereSlamRelocApi::class)

package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.sphereslam.reloc.TrackingFailureEvent
import com.hereliesaz.sphereslam.reloc.TrackingFailureReason

/*
 * The standalone failure taxonomy is SphereSLAM's (`reloc.TrackingFailure*`, ported from this file):
 * reasons, severities and the classifier that maps pose rejections and exceptions onto them. The
 * library deliberately carries no user-facing wording, so the app's on-screen text lives here as
 * [userMessage].
 */
internal typealias StandaloneFailureSeverity = com.hereliesaz.sphereslam.reloc.TrackingFailureSeverity
internal typealias StandaloneFailureReason = TrackingFailureReason
internal typealias StandaloneFailureEvent = TrackingFailureEvent
internal typealias StandaloneFailureClassifier = com.hereliesaz.sphereslam.reloc.TrackingFailureClassifier

/** The artist-facing explanation of this failure. */
internal val TrackingFailureEvent.userMessage: String
    get() = when (reason) {
        TrackingFailureReason.NATIVE_LIBRARY_UNAVAILABLE ->
            "SphereSLAM isn't available in this build."
        TrackingFailureReason.CAMERA_UNAVAILABLE ->
            "Camera isn't available for standalone AR."
        TrackingFailureReason.INTRINSICS_UNAVAILABLE ->
            "Camera calibration is unavailable for standalone AR."
        TrackingFailureReason.REFERENCE_TOO_WEAK ->
            "That wall target has too little trackable detail. Capture a richer patch."
        TrackingFailureReason.NO_CURRENT_PAGE_MATCH ->
            "Keep the wall target in view."
        TrackingFailureReason.STALE_OBSERVATION ->
            "Tracking is lagging. Move the phone more slowly."
        TrackingFailureReason.EXCESSIVE_REPROJECTION_ERROR ->
            "Wall match is unstable. Reframe the target."
        TrackingFailureReason.INSUFFICIENT_INLIERS ->
            "Not enough target detail is visible."
        TrackingFailureReason.POSE_JUMP ->
            "Tracking jump rejected. Hold on the wall target."
        TrackingFailureReason.PERSISTED_TARGET_CORRUPT ->
            "Saved wall target is missing or unreadable. Capture a new target."
        TrackingFailureReason.INTERNAL_FAILURE ->
            "SphereSLAM couldn't continue tracking this target."
    }
