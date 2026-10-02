package com.hereliesaz.graffitixr.feature.ar.anchor

import com.hereliesaz.sphereslam.SphereSlamPoseMath
import com.hereliesaz.sphereslam.SphereSlamTracker

/**
 * Pure frame algebra and quality gates for ARCore-primary / KPM-correction hybrid tracking.
 *
 * Inputs are deliberately wall-local:
 * - KPM gives camera-from-page at the observation timestamp;
 * - [pageFromAnchor] is frozen once from two simultaneously tracked ARCore anchors;
 * - [HybridPoseHistory] gives ARCore camera/world + artwork-backbone at that SAME timestamp.
 *
 * The result is a corrected artwork anchor in the observation's ARCore world frame plus the
 * solve-time backbone needed to convert it to PoseFusion's rebase-invariant local correction.
 */
object HybridKpmCorrection {
    const val MIN_INLIERS = 12
    const val MAX_REPROJECTION_ERROR_PX = 4f
    const val MAX_OBSERVATION_AGE_NS = 500_000_000L
    const val MAX_POSE_PAIR_DELTA_NS = 40_000_000L

    enum class Reject {
        NON_METRIC,
        STALE,
        NO_POSE_PAIR,
        TOO_FEW_INLIERS,
        BAD_REPROJECTION,
        NON_FINITE,
    }

    data class Accepted(
        val timestampNs: Long,
        val correctedAnchorWorld: FloatArray,
        val backboneAtObservation: FloatArray,
        val confidence: Float,
        val inliers: Int,
    )

    data class Decision(
        val accepted: Accepted? = null,
        val reject: Reject? = null,
    )

    fun solve(
        observation: SphereSlamTracker.Observation,
        pageGeometry: SphereSlamPoseMath.PageGeometry?,
        physicallyMetric: Boolean,
        pageFromAnchor: FloatArray?,
        poseHistory: HybridPoseHistory,
        currentFrameTimestampNs: Long,
    ): Decision {
        if (!physicallyMetric || pageGeometry == null || pageFromAnchor?.size != 16) {
            return Decision(reject = Reject.NON_METRIC)
        }
        if (
            currentFrameTimestampNs <= 0L ||
            observation.timestampNs <= 0L ||
            observation.timestampNs > currentFrameTimestampNs ||
            currentFrameTimestampNs - observation.timestampNs > MAX_OBSERVATION_AGE_NS
        ) {
            return Decision(reject = Reject.STALE)
        }
        if (observation.inliers < MIN_INLIERS) {
            return Decision(reject = Reject.TOO_FEW_INLIERS)
        }
        if (
            !observation.error.isFinite() ||
            observation.error < 0f ||
            observation.error > MAX_REPROJECTION_ERROR_PX
        ) {
            return Decision(reject = Reject.BAD_REPROJECTION)
        }
        if (
            observation.pageToCamera3x4.any { !it.isFinite() } ||
            pageFromAnchor.any { !it.isFinite() }
        ) {
            return Decision(reject = Reject.NON_FINITE)
        }

        val sample = poseHistory.nearest(
            observation.timestampNs,
            MAX_POSE_PAIR_DELTA_NS,
        ) ?: return Decision(reject = Reject.NO_POSE_PAIR)

        val cameraFromPage = SphereSlamPoseMath.pageToOpenGlViewMeters(
            observation.pageToCamera3x4,
            pageCenterXmm = pageGeometry.centerXmm,
            pageCenterYmm = pageGeometry.centerYmm,
        )
        val cameraFromAnchor = PoseMath.multiply(cameraFromPage, pageFromAnchor)
        val correctedWorld = PoseMath.multiply(
            PoseMath.rigidInverse(sample.viewMatrix),
            cameraFromAnchor,
        )
        if (correctedWorld.any { !it.isFinite() }) {
            return Decision(reject = Reject.NON_FINITE)
        }

        // Independent confidence terms. Inliers saturate at the same 20-point bar PoseFusion uses
        // for hard relocks; reprojection confidence falls linearly to zero at the acceptance edge.
        val inlierConfidence = (observation.inliers / 20f).coerceIn(0f, 1f)
        val reprojectionConfidence =
            (1f - observation.error / MAX_REPROJECTION_ERROR_PX).coerceIn(0f, 1f)
        val confidence = (inlierConfidence * reprojectionConfidence).coerceIn(0f, 1f)

        return Decision(
            accepted = Accepted(
                timestampNs = observation.timestampNs,
                correctedAnchorWorld = correctedWorld,
                backboneAtObservation = sample.backboneMatrix.copyOf(),
                confidence = confidence,
                inliers = observation.inliers,
            )
        )
    }
}
