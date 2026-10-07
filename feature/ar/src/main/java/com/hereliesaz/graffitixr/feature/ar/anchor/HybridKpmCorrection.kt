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
 *
 * SphereSLAM 0.23.5 intentionally owns construction of [SphereSlamTracker.Observation]. Production
 * consumes that public result type directly; the raw-parameter seam below exists only so GraffitiXR
 * can keep deterministic unit coverage of its own fusion policy without depending on a hidden
 * SphereSLAM test constructor.
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

    /** Production entry point: consume the immutable observation published by SphereSLAM. */
    fun solve(
        observation: SphereSlamTracker.Observation,
        pageGeometry: SphereSlamPoseMath.PageGeometry?,
        physicallyMetric: Boolean,
        pageFromAnchor: FloatArray?,
        poseHistory: HybridPoseHistory,
        currentFrameTimestampNs: Long,
    ): Decision = solveRaw(
        timestampNs = observation.timestampNs,
        inliers = observation.inliers,
        reprojectionError = observation.error,
        cameraFromPage3x4 = observation.cameraFromPage3x4,
        pageGeometry = pageGeometry,
        physicallyMetric = physicallyMetric,
        pageFromAnchor = pageFromAnchor,
        poseHistory = poseHistory,
        currentFrameTimestampNs = currentFrameTimestampNs,
    )

    /**
     * GraffitiXR-owned test seam for the fusion policy. This deliberately mirrors only the public
     * values read from SphereSLAM's observation; it is not a replacement tracker abstraction.
     */
    internal fun solveRaw(
        timestampNs: Long,
        inliers: Int,
        reprojectionError: Float,
        cameraFromPage3x4: FloatArray,
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
            timestampNs <= 0L ||
            timestampNs > currentFrameTimestampNs ||
            currentFrameTimestampNs - timestampNs > MAX_OBSERVATION_AGE_NS
        ) {
            return Decision(reject = Reject.STALE)
        }
        if (inliers < MIN_INLIERS) {
            return Decision(reject = Reject.TOO_FEW_INLIERS)
        }
        if (
            !reprojectionError.isFinite() ||
            reprojectionError < 0f ||
            reprojectionError > MAX_REPROJECTION_ERROR_PX
        ) {
            return Decision(reject = Reject.BAD_REPROJECTION)
        }
        if (
            cameraFromPage3x4.size != 12 ||
            cameraFromPage3x4.any { !it.isFinite() } ||
            pageFromAnchor.any { !it.isFinite() }
        ) {
            return Decision(reject = Reject.NON_FINITE)
        }

        val sample = poseHistory.nearest(
            timestampNs,
            MAX_POSE_PAIR_DELTA_NS,
        ) ?: return Decision(reject = Reject.NO_POSE_PAIR)

        val cameraFromPage = SphereSlamPoseMath.pageToOpenGlViewMeters(
            cameraFromPage3x4,
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
        val inlierConfidence = (inliers / 20f).coerceIn(0f, 1f)
        val reprojectionConfidence =
            (1f - reprojectionError / MAX_REPROJECTION_ERROR_PX).coerceIn(0f, 1f)
        val confidence = (inlierConfidence * reprojectionConfidence).coerceIn(0f, 1f)

        return Decision(
            accepted = Accepted(
                timestampNs = timestampNs,
                correctedAnchorWorld = correctedWorld,
                backboneAtObservation = sample.backboneMatrix.copyOf(),
                confidence = confidence,
                inliers = inliers,
            )
        )
    }
}
