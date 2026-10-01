package com.hereliesaz.graffitixr.feature.ar

/**
 * App-level acceptance gate for a standalone KPM pose.
 *
 * Defaults intentionally mirror the pinned artoolkitX binary KPM implementation:
 * - kpmUtilGetPose_binary rejects fewer than four matches;
 * - it rejects ICP error > 10.0.
 *
 * Keeping the same limits here makes the contract explicit and configurable at the app boundary,
 * and protects us if the native wrapper later changes which KpmResult it exposes.
 */
data class StandalonePoseAcceptanceConfig(
    val minInliers: Int = 4,
    val maxReprojectionError: Float = 10f,
) {
    init {
        require(minInliers >= 4)
        require(maxReprojectionError.isFinite() && maxReprojectionError > 0f)
    }
}

enum class StandalonePoseRejection {
    NON_FINITE,
    TOO_FEW_INLIERS,
    EXCESSIVE_REPROJECTION_ERROR,
}

data class StandalonePoseAcceptance(
    val accepted: Boolean,
    val rejection: StandalonePoseRejection? = null,
)

internal class StandalonePoseAcceptancePolicy(
    private val config: StandalonePoseAcceptanceConfig = StandalonePoseAcceptanceConfig(),
) {
    fun evaluate(
        viewMatrix: FloatArray,
        inlierCount: Int,
        reprojectionError: Float,
    ): StandalonePoseAcceptance {
        if (viewMatrix.size != 16 || viewMatrix.any { !it.isFinite() } || !reprojectionError.isFinite()) {
            return StandalonePoseAcceptance(false, StandalonePoseRejection.NON_FINITE)
        }
        if (inlierCount < config.minInliers) {
            return StandalonePoseAcceptance(false, StandalonePoseRejection.TOO_FEW_INLIERS)
        }
        if (reprojectionError > config.maxReprojectionError) {
            return StandalonePoseAcceptance(false, StandalonePoseRejection.EXCESSIVE_REPROJECTION_ERROR)
        }
        return StandalonePoseAcceptance(true)
    }
}
