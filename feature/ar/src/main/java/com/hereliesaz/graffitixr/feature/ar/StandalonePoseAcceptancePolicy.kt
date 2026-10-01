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
    val maxTranslationPageWidthsPerFrame: Float = 2f,
    val maxAngularJumpDegrees: Float = 90f,
    val reacquireMaxTranslationPageWidths: Float = 8f,
    val reacquireMaxAngularJumpDegrees: Float = 175f,
) {
    init {
        require(minInliers >= 4)
        require(maxReprojectionError.isFinite() && maxReprojectionError > 0f)
        require(maxTranslationPageWidthsPerFrame.isFinite() && maxTranslationPageWidthsPerFrame > 0f)
        require(maxAngularJumpDegrees in 0f..180f)
        require(reacquireMaxTranslationPageWidths.isFinite() && reacquireMaxTranslationPageWidths > 0f)
        require(reacquireMaxAngularJumpDegrees in 0f..180f)
    }
}

enum class StandalonePoseRejection {
    NON_FINITE,
    TOO_FEW_INLIERS,
    EXCESSIVE_REPROJECTION_ERROR,
    TRANSLATION_JUMP,
    ANGULAR_JUMP,
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
        previousViewMatrix: FloatArray? = null,
        referenceWidthUnits: Float = 1f,
        reacquiring: Boolean = false,
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

        if (previousViewMatrix != null) {
            if (previousViewMatrix.size != 16 || previousViewMatrix.any { !it.isFinite() }) {
                return StandalonePoseAcceptance(false, StandalonePoseRejection.NON_FINITE)
            }
            if (!referenceWidthUnits.isFinite() || referenceWidthUnits <= 0f) {
                return StandalonePoseAcceptance(false, StandalonePoseRejection.NON_FINITE)
            }

            val translationLimit = if (reacquiring) {
                config.reacquireMaxTranslationPageWidths
            } else {
                config.maxTranslationPageWidthsPerFrame
            }
            val angularLimit = if (reacquiring) {
                config.reacquireMaxAngularJumpDegrees
            } else {
                config.maxAngularJumpDegrees
            }

            val translationPageWidths =
                cameraCenterDistance(previousViewMatrix, viewMatrix) / referenceWidthUnits
            if (translationPageWidths > translationLimit) {
                return StandalonePoseAcceptance(false, StandalonePoseRejection.TRANSLATION_JUMP)
            }
            if (rotationDeltaDegrees(previousViewMatrix, viewMatrix) > angularLimit) {
                return StandalonePoseAcceptance(false, StandalonePoseRejection.ANGULAR_JUMP)
            }
        }
        return StandalonePoseAcceptance(true)
    }

    private fun cameraCenterDistance(a: FloatArray, b: FloatArray): Float {
        val ac = cameraCenter(a)
        val bc = cameraCenter(b)
        val dx = ac[0] - bc[0]
        val dy = ac[1] - bc[1]
        val dz = ac[2] - bc[2]
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun cameraCenter(view: FloatArray): FloatArray {
        val tx = view[12]
        val ty = view[13]
        val tz = view[14]
        return floatArrayOf(
            -(view[0] * tx + view[1] * ty + view[2] * tz),
            -(view[4] * tx + view[5] * ty + view[6] * tz),
            -(view[8] * tx + view[9] * ty + view[10] * tz),
        )
    }

    private fun rotationDeltaDegrees(a: FloatArray, b: FloatArray): Float {
        val trace =
            (a[0] * b[0] + a[4] * b[4] + a[8] * b[8]) +
                (a[1] * b[1] + a[5] * b[5] + a[9] * b[9]) +
                (a[2] * b[2] + a[6] * b[6] + a[10] * b[10])
        val cosine = ((trace - 1f) * 0.5f).coerceIn(-1f, 1f)
        return Math.toDegrees(kotlin.math.acos(cosine).toDouble()).toFloat()
    }
}
