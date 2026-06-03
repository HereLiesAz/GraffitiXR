package com.hereliesaz.graffitixr.feature.ar.anchor

/**
 * Fuses the ARCore-consensus backbone with smoothed, confidence-weighted mark-PnP corrections into a
 * single rendered anchor model matrix (ARCore world frame). Stateful only across frames (last seq +
 * current smoothed pose); the geometry is pure (see [composeCorrected]/[blend]).
 */
class PoseFusion {
    private var lastSeq = 0f
    private var smoothed: FloatArray? = null
    // True until the first confident relocalization after a (re)start or tracking loss. The first
    // confident snap is applied HARD (no smoothing) so a cold relock — out of a pocket, screen back on —
    // is instantaneous; subsequent in-session corrections are smoothed to avoid jitter.
    private var coldStart = true

    /** Re-arm the cold (hard) snap — call on session resume / tracking loss so the next relock is instant. */
    fun markRelocalizing() { coldStart = true }

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
     * @param confGlobal global splat confidence in [0,1] — reserved (unused: with the ML depth API off
     *        the voxel map is empty so this is ~0; correction strength is driven by PnP inlier ratio).
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
        // Cold relock snaps hard (instant); warm corrections ease in by inlier-ratio-scaled alpha.
        val alpha = if (coldStart) 1f else (BASE_ALPHA * inlierRatio).coerceIn(0f, 1f)
        coldStart = false
        val out = blend(base, corrected, alpha)
        smoothed = out
        return out
    }
}
