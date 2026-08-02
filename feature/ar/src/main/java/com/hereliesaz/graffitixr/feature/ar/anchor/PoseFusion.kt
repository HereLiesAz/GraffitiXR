package com.hereliesaz.graffitixr.feature.ar.anchor

import com.hereliesaz.graffitixr.common.model.FusionDiagnostics
import com.hereliesaz.graffitixr.common.model.FusionState

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
    // Persistent world-frame drift correction D such that fused = D ∘ backbone. Held between snaps and
    // re-applied every frame so the overlay stays locked to the PnP-corrected world location even on
    // frames with no new snap. Null until the first trusted relocalization.
    private var correction: FloatArray? = null
    // True until the first HARD relock after a (re)start or tracking loss, so a cold relock — out of a
    // pocket, screen back on — snaps instantly instead of easing in.
    private var coldStart = true

    /** Re-arm the cold (hard) snap — call on session resume / tracking loss so the next relock is instant. */
    fun markRelocalizing() { coldStart = true }

    companion object {
        /** Minimum inlier ratio for a snap to be trusted at all. */
        const val MIN_INLIER_RATIO = 0.5f
        /** Base smoothing rate for a (non-cold) correction update. */
        const val BASE_ALPHA = 0.25f
        /**
         * Corroboration floor. `effConf = CONF_FLOOR + (1 - CONF_FLOOR) * confGlobal`, so
         * corroboration can *raise* trust toward 1.0 but can never drop it below the floor — an
         * unpainted wall still corrects, driven by the inlier ratio alone.
         *
         * ## `IMPLEMENTATION.md` 5b.2 — re-derived, and the old rationale did not survive
         *
         * This used to say: "at 0.5 a fully corroborated wall pulls exactly twice as hard as a bare
         * one." That sentence was true of the OLD input and is misleading about the new one.
         *
         * Before Phase 5b, `confGlobal` was painting progress with a whole-design denominator —
         * `matched / artDescs.rows`. Its ceiling of 1.0 was at least *conceptually* reachable: paint
         * the whole mural and the wall answers for every design feature. The 2x followed.
         *
         * It is now `matched / predicted`, the corroboration confidence over the features the
         * current pose predicts are visible. **That ceiling is not reachable on any real wall.** Its
         * maximum is bounded by how much of a painted design the detector actually re-finds, and
         * three separate things hold that below 1: descriptor repeatability across a repaint, the
         * lighting difference between registration and painting, and — since Phase 4 — the
         * lone-candidate skip, which deflates `matched` without touching `predicted` whenever a
         * predicted feature's neighbourhood holds fewer than two candidates.
         *
         * So `effConf` does not span `[0.5, 1.0]` in practice; it spans `[0.5, 0.5 + 0.5·m]` where
         * `m` is that achievable maximum. **The 2x is arithmetic at an input the system cannot
         * produce, not a property of the system.** If `m` turns out to be 0.6, a well-painted wall
         * pulls 1.6x a bare one, not 2x.
         *
         * ## Why the number is still 0.5
         *
         * Because `m` has never been measured, and picking a floor to compensate for an unknown
         * ceiling is guessing dressed as derivation. The two arguments also point opposite ways: the
         * new signal moves per *frame* in both directions where progress moved over hours, which
         * argues for a HIGHER floor so a momentary dip does not slash correction — while the entire
         * point of splitting confidence from progress was to let correction scale by something
         * trustworthy, which argues for a LOWER one and a wider dynamic range. Only a measurement
         * settles that.
         *
         * **E11 sets it, and it must measure `m` first** — the floor and the achievable maximum
         * jointly determine the real dynamic range, so a sweep of the floor alone would report the
         * wrong contour. `PARAMETERS.md` §2 carries this as the parameter's basis.
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
        /**
         * `IMPLEMENTATION.md` **0.9** — the relocalized anchor pose in the live world frame.
         *
         * ```
         * corrected = inv(vCurrent) · [D · pnpMat] · captureAnchorCam
         * ```
         *
         * Two things were wrong with the form this replaces, `inv(vCurrent) · pnpMat · fpAnchor`:
         *
         *  - `pnpMat` comes out of `solvePnP` in the **CV** convention (+Y down, +Z forward) and was
         *    being chained straight onto a **GL** view inverse, so Y and Z were flipped;
         *  - its domain is the **fingerprint** frame — the display-oriented CV camera at capture —
         *    while `fpAnchor` was a world-space model matrix, so the capture view was missing
         *    entirely.
         *
         * [captureAnchorCam] repairs the second and half of the first at once. It is
         * `V_cv(capture) · anchorModel`, which Phase 2 already builds, persists on the
         * `Fingerprint`, and partitions the footprint with — so this needs no new geometry, only a
         * quantity the project already computes for another reason.
         *
         * **`D` goes on ONE side, not both.** Converting a *transform* between conventions is
         * normally the conjugation `D · m · D`, and that is what I wrote first. It is wrong here
         * because `captureAnchorCam` carries its own `D` (it is a CV view times a model matrix), so
         * the trailing factor applies it twice — landing 4.29 from the anchor where the original
         * defect was 2.92, i.e. *worse than doing nothing*. `PAPER.md` §8.3's warning about getting
         * every sign right is not rhetorical; `ComposeCorrectedFrameTest` pins both mistakes.
         *
         * Verified by a zero-drift invariant rather than by inspection: with no drift the correction
         * must be the identity, so this must return `anchorModel` unchanged. It does, to 4.8e-7.
         *
         * @param captureAnchorCam `Fingerprint.captureAnchorCam` — NOT the live anchor pose and NOT
         *   `getFingerprintAnchor()`. Passing a world-frame anchor here is the defect being fixed.
         */
        fun composeCorrected(
            vCurrent: FloatArray,
            pnpMat: FloatArray,
            captureAnchorCam: FloatArray,
        ): FloatArray = PoseMath.multiply(
            PoseMath.rigidInverse(vCurrent),
            PoseMath.multiply(MetricMarks.glViewToCv(pnpMat), captureAnchorCam),
        )

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
     * @param captureAnchorCam `Fingerprint.captureAnchorCam` — the anchor's pose in the CAPTURE
     *   camera's CV frame, `V_cv(capture) · anchorModel`. Renamed from `fpAnchor` as part of 0.9:
     *   the old name and the old value were both a world-frame model matrix, which is the frame
     *   error that item fixes. See [composeCorrected].
     * @param confGlobal teleological corroboration in [0,1] — the fraction of the registered artwork's
     *        features the real wall currently answers for (MobileGS painting progress). Raises smooth
     *        correction strength from [CONF_FLOOR] at 0% painted to full at 100%, which is the
     *        "the further along, the tighter it locks" behaviour. Never lowers it below the floor, so
     *        a bare wall still corrects on the PnP inlier ratio alone.
     */
    fun currentAnchor(
        backbone: FloatArray,
        vCurrent: FloatArray,
        reloc: FloatArray,
        captureAnchorCam: FloatArray,
        confGlobal: Float,
    ): FloatArray {
        val seq = reloc[18]
        val matchCount = reloc[17]
        val inliers = reloc[16]
        val inlierRatio = if (matchCount > 0f) inliers / matchCount else 0f
        val isNew = seq > 0f && seq != lastSeq

        // Recorded even when the relock is refused below. A relocalization that reached fusion and
        // was thrown away by MIN_INLIER_RATIO is a different fault from one that never arrived, and
        // the two look identical from every other channel — the overlay drifts either way.
        if (isNew) lastInlierRatio = inlierRatio
        if (isNew && inlierRatio < MIN_INLIER_RATIO) snapsRejected++

        if (isNew && inlierRatio >= MIN_INLIER_RATIO) {
            val corrected = composeCorrected(vCurrent, reloc.copyOf(16), captureAnchorCam)
            // World-frame drift correction such that D ∘ backbone == corrected at snap time.
            val newD = PoseMath.multiply(corrected, PoseMath.rigidInverse(backbone))

            // Cold = first lock after (re)start/tracking-loss, no prior correction, or a fix that
            // diverges far from where we currently draw (the pocket case). A confident cold fix snaps
            // hard for instant relock; everything else eases in by inlier-ratio-scaled alpha.
            val applied = correction?.let { PoseMath.multiply(it, backbone) }
            val cold = coldStart || applied == null || diverged(applied, corrected)
            val highConf = inlierRatio >= COLD_SNAP_INLIER_RATIO && inliers >= COLD_SNAP_MIN_INLIERS

            correction = if (cold && highConf) {
                coldStart = false
                lastState = FusionState.COLD_SNAP
                lastAlpha = -1f          // a snap has no blend rate; -1 says so rather than 1.0
                newD // instant relock
            } else {
                val effConf = (CONF_FLOOR + (1f - CONF_FLOOR) * confGlobal.coerceIn(0f, 1f))
                val alpha = (BASE_ALPHA * inlierRatio * effConf).coerceIn(0f, 1f)
                lastState = FusionState.BLENDING
                lastAlpha = alpha
                blend(correction ?: identity(), newD, alpha)
            }
            snapsAccepted++
        } else if (correction != null) {
            // A correction stands but nothing new arrived. The healthy steady state — and also what
            // a stale correction looks like, which is why snapsAccepted is published beside it.
            lastState = FusionState.HOLDING
        }
        lastSeq = seq
        // fused = D ∘ backbone, re-applied every frame (identity drift until the first trusted snap).
        return PoseMath.multiply(correction ?: identity(), backbone)
    }

    private var lastState = FusionState.WAITING_FOR_LOCK
    private var lastAlpha = -1f
    private var lastInlierRatio = -1f
    private var snapsAccepted = 0
    private var snapsRejected = 0

    /**
     * A snapshot of the last decision, for the diagnostic overlay and the eval CSV.
     *
     * The magnitudes are computed here rather than stored, because the correction is a matrix and
     * "how far is it pulling the overlay" is the question a reader actually has. Millimetres and
     * degrees, both -1 when no correction stands.
     *
     * Note this reports what fusion did when it RAN. The states that mean it did not run at all —
     * disabled, no anchor, no capture pose — are the caller's to report, because only the caller
     * knows them; see `ArRenderer`.
     */
    fun diagnostics(): FusionDiagnostics {
        val d = correction
        val mm: Float
        val deg: Float
        if (d == null) {
            mm = -1f; deg = -1f
        } else {
            val t = PoseMath.translationOf(d)
            mm = kotlin.math.sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]) * 1000f
            val q = PoseMath.matrixToQuaternion(d)
            // |w| because q and -q are the same rotation; without it a correction just past 180 deg
            // would report as a tiny one.
            val w = kotlin.math.abs(q[3]).coerceIn(0f, 1f)
            deg = Math.toDegrees(2.0 * kotlin.math.acos(w.toDouble())).toFloat()
        }
        return FusionDiagnostics(
            state = if (d == null && lastState == FusionState.WAITING_FOR_LOCK) {
                FusionState.WAITING_FOR_LOCK
            } else {
                lastState
            },
            lastAlpha = lastAlpha,
            lastInlierRatio = lastInlierRatio,
            correctionMm = mm,
            correctionDeg = deg,
            snapsAccepted = snapsAccepted,
            snapsRejected = snapsRejected,
        )
    }
}
