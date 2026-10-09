package com.hereliesaz.graffitixr.feature.ar.anchor

import com.hereliesaz.graffitixr.common.model.HybridKpmDiagnostics
import com.hereliesaz.graffitixr.common.model.HybridKpmOutcome
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
 * Gates run in a fixed order and every [Decision] carries a [HybridKpmDiagnostics] payload with the
 * values computed up to the gate that decided it, so a rejection is a diagnosis rather than an
 * absence. This object is stateless: the repeated-observation agreement required before a LARGE
 * correction may snap needs memory across observations and lives in [PoseFusion].
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

    /**
     * Hard ceiling on the anchor-local correction one observation may imply (metres).
     *
     * The correction is ARCore's artwork-anchor drift as KPM measures it. ARCore drift on a wall
     * accumulates to centimetres or a few decimetres; a metre means the observation is a different
     * wall, a different instance of a repeated pattern, or a broken frame pairing — none of which
     * high inlier counts rule out. `PoseFusion.COLD_SNAP_DIST_M` only CLASSIFIES a large correction
     * as a relock; this REFUSES an implausible one. First-pass value, not yet measured on device
     * (docs/SPHERESLAM_TODO.md §10 device items).
     */
    const val MAX_CORRECTION_M = 1.0f

    /** Rotation counterpart of [MAX_CORRECTION_M] (degrees). Same status: unmeasured first pass. */
    const val MAX_CORRECTION_DEG = 30f

    /** Order is not persisted; [CORRECTION_TOO_LARGE] was appended with the hard ceiling. */
    enum class Reject {
        NON_METRIC,
        STALE,
        NO_POSE_PAIR,
        TOO_FEW_INLIERS,
        BAD_REPROJECTION,
        NON_FINITE,
        CORRECTION_TOO_LARGE,
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
        val diagnostics: HybridKpmDiagnostics = HybridKpmDiagnostics(),
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
        // Payload grows as gates pass; each reject reports exactly what was known at its gate.
        var diag = HybridKpmDiagnostics(
            observationTimestampNs = timestampNs,
            ageMs = if (currentFrameTimestampNs > 0L && timestampNs > 0L) {
                (currentFrameTimestampNs - timestampNs) / 1_000_000f
            } else {
                -1f
            },
            inliers = inliers,
            reprojectionPx = if (reprojectionError.isFinite()) reprojectionError else -1f,
        )
        fun reject(r: Reject) = Decision(reject = r, diagnostics = diag.copy(outcome = r.outcome()))

        if (!physicallyMetric || pageGeometry == null || pageFromAnchor?.size != 16) {
            return reject(Reject.NON_METRIC)
        }
        if (
            currentFrameTimestampNs <= 0L ||
            timestampNs <= 0L ||
            timestampNs > currentFrameTimestampNs ||
            currentFrameTimestampNs - timestampNs > MAX_OBSERVATION_AGE_NS
        ) {
            return reject(Reject.STALE)
        }
        if (inliers < MIN_INLIERS) {
            return reject(Reject.TOO_FEW_INLIERS)
        }
        if (
            !reprojectionError.isFinite() ||
            reprojectionError < 0f ||
            reprojectionError > MAX_REPROJECTION_ERROR_PX
        ) {
            return reject(Reject.BAD_REPROJECTION)
        }
        if (
            cameraFromPage3x4.size != 12 ||
            cameraFromPage3x4.any { !it.isFinite() } ||
            pageFromAnchor.any { !it.isFinite() }
        ) {
            return reject(Reject.NON_FINITE)
        }

        val sample = poseHistory.nearest(
            timestampNs,
            MAX_POSE_PAIR_DELTA_NS,
        ) ?: return reject(Reject.NO_POSE_PAIR)

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
            return reject(Reject.NON_FINITE)
        }

        // The same anchor-local quantity PoseFusion stores: backbone⁻¹ ∘ corrected at the
        // observation instant. Its magnitude is the drift being corrected, and is invariant under an
        // ARCore global rebase, so the ceiling cannot be tripped by ARCore renumbering its world.
        val local = PoseMath.multiply(PoseMath.rigidInverse(sample.backboneMatrix), correctedWorld)
        val correctionM = PoseMath.translationNorm(local)
        val correctionDeg = PoseMath.rotationAngleDeg(local)
        diag = diag.copy(correctionMm = correctionM * 1000f, correctionDeg = correctionDeg)
        if (
            !correctionM.isFinite() || !correctionDeg.isFinite() ||
            correctionM > MAX_CORRECTION_M || correctionDeg > MAX_CORRECTION_DEG
        ) {
            return reject(Reject.CORRECTION_TOO_LARGE)
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
            ),
            diagnostics = diag.copy(outcome = HybridKpmOutcome.ACCEPTED),
        )
    }

    private fun Reject.outcome(): HybridKpmOutcome = when (this) {
        Reject.NON_METRIC -> HybridKpmOutcome.NON_METRIC
        Reject.STALE -> HybridKpmOutcome.STALE
        Reject.NO_POSE_PAIR -> HybridKpmOutcome.NO_POSE_PAIR
        Reject.TOO_FEW_INLIERS -> HybridKpmOutcome.TOO_FEW_INLIERS
        Reject.BAD_REPROJECTION -> HybridKpmOutcome.BAD_REPROJECTION
        Reject.NON_FINITE -> HybridKpmOutcome.NON_FINITE
        Reject.CORRECTION_TOO_LARGE -> HybridKpmOutcome.CORRECTION_TOO_LARGE
    }
}
