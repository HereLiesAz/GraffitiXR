package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StandaloneFailureClassifierTest {

    @Test
    fun `visual quality failures stay transient`() {
        for (reason in listOf(
            StandaloneFailureReason.NO_CURRENT_PAGE_MATCH,
            StandaloneFailureReason.STALE_OBSERVATION,
            StandaloneFailureReason.EXCESSIVE_REPROJECTION_ERROR,
            StandaloneFailureReason.INSUFFICIENT_INLIERS,
            StandaloneFailureReason.POSE_JUMP,
        )) {
            assertEquals(
                StandaloneFailureSeverity.TRANSIENT,
                StandaloneFailureClassifier.event(reason).severity,
            )
        }
    }

    @Test
    fun `camera reference and persisted target failures are recoverable`() {
        for (reason in listOf(
            StandaloneFailureReason.CAMERA_UNAVAILABLE,
            StandaloneFailureReason.INTRINSICS_UNAVAILABLE,
            StandaloneFailureReason.REFERENCE_TOO_WEAK,
            StandaloneFailureReason.PERSISTED_TARGET_CORRUPT,
        )) {
            assertEquals(
                StandaloneFailureSeverity.RECOVERABLE,
                StandaloneFailureClassifier.event(reason).severity,
            )
        }
    }

    @Test
    fun `native linkage failure is fatal and explicit`() {
        val event = StandaloneFailureClassifier.fromThrowable(
            UnsatisfiedLinkError("missing libarx"),
        )
        assertEquals(StandaloneFailureReason.NATIVE_LIBRARY_UNAVAILABLE, event.reason)
        assertEquals(StandaloneFailureSeverity.FATAL, event.severity)
        assertTrue(event.userMessage.contains("isn't available"))
        assertTrue(event.diagnostic.contains("missing libarx"))
    }

    @Test
    fun `KPM rejection reasons map to artist-facing categories`() {
        assertEquals(
            StandaloneFailureReason.INSUFFICIENT_INLIERS,
            StandaloneFailureClassifier.fromPoseRejection(
                StandalonePoseRejection.TOO_FEW_INLIERS,
                "inliers=3",
            ).reason,
        )
        assertEquals(
            StandaloneFailureReason.EXCESSIVE_REPROJECTION_ERROR,
            StandaloneFailureClassifier.fromPoseRejection(
                StandalonePoseRejection.EXCESSIVE_REPROJECTION_ERROR,
                "error=12",
            ).reason,
        )
        assertEquals(
            StandaloneFailureReason.POSE_JUMP,
            StandaloneFailureClassifier.fromPoseRejection(
                StandalonePoseRejection.ANGULAR_JUMP,
                "angle=100",
            ).reason,
        )
    }

    @Test
    fun `weak reference keeps native feature detail`() {
        val event = StandaloneFailureClassifier.fromThrowable(
            StandaloneReferenceTooWeakException(
                featureCount = 7,
                minimumFeatureCount = 16,
            ),
        )
        assertEquals(StandaloneFailureReason.REFERENCE_TOO_WEAK, event.reason)
        assertEquals(StandaloneFailureSeverity.RECOVERABLE, event.severity)
        assertTrue(event.diagnostic.contains("features=7"))
        assertTrue(event.diagnostic.contains("minimum=16"))
    }
}
