package com.hereliesaz.graffitixr.feature.ar.anchor

/**
 * Fuses the ARCore-consensus backbone with mark-PnP relocalization into a single rendered anchor
 * model matrix (ARCore world frame).
 *
 * The PnP fix is modelled as a **persistent world-frame drift correction** `D = corrected ∘
 * backbone⁻¹`, held between snaps and re-applied to the live backbone every frame
 * (`fused = D ∘ backbone`). So the overlay stays locked to the PnP-corrected world location even as
 * ARCore's frame drifts and even on frames where no new snap arrives — the previous design reset to
 * the raw backbone each non-snap frame, which washed out every correction.
 *
 * Correction strength is driven by the **PnP inlier ratio** (not splat confidence, which is ~0 when
 * the depth API is off and would otherwise zero the correction). A confident relock that is *cold*
 * — the first lock, or one that diverges far from where we're currently drawing (the pocket case) —
 * **hard-snaps** (D replaced outright) for instant relocalization; otherwise D is smoothed toward
 * the new fix.
 *
 * Stateful only across frames (last seq + current correction); the geometry is pure.
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
        /** Minimum inlier ratio for a snap to be trusted at all. */
        const val MIN_INLIER_RATIO = 0.5f
        /** Base smoothing rate for a (non-cold) correction update. */
        const val BASE_ALPHA = 0.25f
        /**
         * Splat-confidence floor. Effective confidence = CONF_FLOOR + (1-CONF_FLOOR)*confGlobal, so
         * map maturity can *raise* trust toward 1.0 but can never drop it below the floor — depth-off
         * (confGlobal≈0) still corrects, driven by the inlier ratio.
         */
        const val CONF_FLOOR = 0.5f
        /** A cold (hard) snap requires at least this inlier ratio … */
        const val COLD_SNAP_INLIER_RATIO = 0.7f
        /** … and at least this many absolute inliers — guards against a confident-but-tiny match. */
        const val COLD_SNAP_MIN_INLIERS = 20f
        /** Translation gap (m) between where we draw now and the new fix that counts as a relock. */
        const val COLD_SNAP_DIST_M = 0.20f
        /** Rotation gap (deg) that counts as a relock. */
        const val COLD_SNAP_ANGLE_DEG = 15f

        private fun identity() = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)

        /** Corrected anchor model matrix in the CURRENT world frame. All inputs rigid. */
        fun composeCorrected(vCurrent: FloatArray, pnpMat: FloatArray, fpAnchor: FloatArray): FloatArray =
            PoseMath.multiply(PoseMath.multiply(PoseMath.rigidInverse(vCurrent), pnpMat), fpAnchor)

        /** Smoothed interpolation between two rigid poses (translation lerp + quaternion nlerp). */
        fun blend(current: FloatArray, target: FloatArray, alpha: Float): FloatArray {
            val t = PoseMath.lerp(PoseMath.translationOf(current), PoseMath.translationOf(target), alpha)
            val q = PoseMath.nlerpQuat(PoseMath.matrixToQuaternion(current), PoseMath.matrixToQuaternion(target), alpha)
            return PoseMath.fromQuaternionTranslation(q, t)
        }

        /** True if two rigid poses differ in translation or rotation beyond the cold-snap thresholds. */
        fun diverged(a: FloatArray, b: FloatArray): Boolean {
            val ta = PoseMath.translationOf(a); val tb = PoseMath.translationOf(b)
            val dx = ta[0] - tb[0]; val dy = ta[1] - tb[1]; val dz = ta[2] - tb[2]
            if (kotlin.math.sqrt(dx * dx + dy * dy + dz * dz) >= COLD_SNAP_DIST_M) return true
            val qa = PoseMath.matrixToQuaternion(a); val qb = PoseMath.matrixToQuaternion(b)
            val dot = kotlin.math.abs(qa[0]*qb[0] + qa[1]*qb[1] + qa[2]*qb[2] + qa[3]*qb[3]).coerceIn(0f, 1f)
            val angleDeg = Math.toDegrees(2.0 * kotlin.math.acos(dot.toDouble())).toFloat()
            return angleDeg >= COLD_SNAP_ANGLE_DEG
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
        val inliers = reloc[16]
        val inlierRatio = if (matchCount > 0f) inliers / matchCount else 0f
        val isNew = seq > 0f && seq != lastSeq

        if (isNew && inlierRatio >= MIN_INLIER_RATIO) {
            lastSeq = seq
            val corrected = composeCorrected(vCurrent, reloc.copyOf(16), fpAnchor)
            // World-frame drift correction such that D ∘ backbone == corrected at snap time.
            val newD = PoseMath.multiply(corrected, PoseMath.rigidInverse(backbone))

            val applied = correction?.let { PoseMath.multiply(it, backbone) }
            val cold = applied == null || diverged(applied, corrected)
            val highConf = inlierRatio >= COLD_SNAP_INLIER_RATIO && inliers >= COLD_SNAP_MIN_INLIERS

            correction = if (cold && highConf) {
                newD // instant relock
            } else {
                val effConf = (CONF_FLOOR + (1f - CONF_FLOOR) * confGlobal.coerceIn(0f, 1f))
                val alpha = (BASE_ALPHA * inlierRatio * effConf).coerceIn(0f, 1f)
                blend(correction ?: identity(), newD, alpha)
            }
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
