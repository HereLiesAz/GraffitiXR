package com.hereliesaz.graffitixr.feature.ar.anchor

/**
 * Fuses the ARCore-consensus backbone with smoothed, confidence-weighted mark-PnP corrections into a
 * single rendered anchor model matrix (ARCore world frame). Stateful only across frames (last seq +
 * current smoothed pose); the geometry is pure (see [composeCorrected]/[blend]).
 */
class PoseFusion {
    private var lastSeq = 0f
    private var smoothed: FloatArray? = null

    companion object {
        const val MIN_INLIER_RATIO = 0.5f
        const val BASE_ALPHA = 0.25f

        /** Corrected anchor model matrix in the CURRENT world frame. All inputs rigid. */
        fun composeCorrected(vCurrent: FloatArray, pnpMat: FloatArray, fpAnchor: FloatArray): FloatArray =
            PoseMath.multiply(PoseMath.multiply(PoseMath.rigidInverse(vCurrent), pnpMat), fpAnchor)

        /** Smoothed interpolation between two rigid poses (translation lerp + quaternion nlerp). */
        fun blend(current: FloatArray, target: FloatArray, alpha: Float): FloatArray {
            val t = PoseMath.lerp(PoseMath.translationOf(current), PoseMath.translationOf(target), alpha)
            val q = PoseMath.nlerpQuat(PoseMath.matrixToQuaternion(current), PoseMath.matrixToQuaternion(target), alpha)
            return PoseMath.fromQuaternionTranslation(q, t)
        }
    }

    /**
     * @param backbone ARCore-consensus model matrix (world frame), the smooth per-frame source
     * @param vCurrent current ARCore view matrix (fresh, GL thread)
     * @param reloc    FloatArray(19): [0..15]=pnpMat, [16]=inlierCount, [17]=matchCount, [18]=seq
     * @param fpAnchor fingerprint-frame anchor model matrix
     * @param confGlobal global splat confidence in [0,1] — map maturity, scales correction strength
     */
    fun currentAnchor(
        backbone: FloatArray,
        vCurrent: FloatArray,
        reloc: FloatArray,
        fpAnchor: FloatArray,
        confGlobal: Float,
    ): FloatArray {
        val seq = reloc[18]
        val matchCount = reloc[17]
        val inlierRatio = if (matchCount > 0f) reloc[16] / matchCount else 0f
        val isNew = seq > 0f && seq != lastSeq
        val base = smoothed ?: backbone

        if (!isNew || inlierRatio < MIN_INLIER_RATIO) {
            smoothed = backbone.copyOf()
            return backbone
        }
        lastSeq = seq
        val pnpMat = reloc.copyOf(16)
        val corrected = composeCorrected(vCurrent, pnpMat, fpAnchor)
        val alpha = (BASE_ALPHA * inlierRatio * confGlobal.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val out = blend(base, corrected, alpha)
        smoothed = out
        return out
    }
}
