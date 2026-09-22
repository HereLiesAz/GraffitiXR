package com.hereliesaz.graffitixr.feature.ar.anchor

import com.hereliesaz.graffitixr.common.model.FusionDiagnostics
import com.hereliesaz.graffitixr.common.model.FusionState

/**
 * Fuses the ARCore-consensus backbone with mark-PnP relocalization into a single rendered anchor
 * model matrix (ARCore world frame).
 *
 * The PnP fix is stored as a **persistent anchor-local correction**
 * `L = backbone⁻¹ ∘ corrected`, then re-applied to the live backbone every frame as
 * `fused = backbone ∘ L`.
 *
 * This side matters. ARCore may rewrite the numerical world coordinate frame between updates while
 * keeping the physical scene fixed. Under a world-frame rebase `G`, both current poses become
 * `backbone' = G ∘ backbone` and `corrected' = G ∘ corrected`; the local correction is unchanged:
 * `backbone'⁻¹ ∘ corrected' = backbone⁻¹ ∘ corrected`. A world-space left correction
 * `D = corrected ∘ backbone⁻¹` would instead need to be conjugated by every unknown rebase
 * (`D' = G ∘ D ∘ G⁻¹`). Persisting the old `D` and applying `D ∘ backbone'` therefore fought
 * ARCore's own coordinate correction.
 *
 * Correction strength is driven by the **PnP inlier ratio** (not splat confidence, which is ~0 when
 * the depth API is off and would otherwise zero the correction). A confident relock that is *cold*
 * — the first lock, or one that diverges far from where we're currently drawing (the pocket case) —
 * **hard-snaps** (L replaced outright) for instant relocalization; otherwise L is smoothed toward
 * the new fix.
 *
 * Stateful only across frames (last seq + current local correction); the geometry is pure.
 */
class PoseFusion {
    private var lastSeq = 0f
    // Persistent ANCHOR-LOCAL correction L such that fused = backbone ∘ L. Unlike a world-frame
    // left correction, L survives ARCore rebasing because the same current backbone carries it into
    // whatever world basis ARCore publishes this frame. Null until the first trusted relocalization.
    private var correction: FloatArray? = null
    // True until the first HARD relock after a (re)start or tracking loss, so a cold relock — out of a
    // pocket, screen back on — snaps instantly instead of easing in.
    private var coldStart = true

    /** Re-arm the cold (hard) snap — call on session resume / tracking loss so the next relock is instant. */
    fun markRelocalizing() { coldStart = true }

    /**
     * Clears the persistent local correction back to its initial (no-correction) state and re-arms
     * the cold-start snap, as if this PoseFusion had just been constructed.
     *
     * Call this when a NEW anchor/fingerprint is established inside an already-running session (the
     * artist re-captures a target). [markRelocalizing] alone does not clear `correction` — by design,
     * a plain tracking loss keeps the standing anchor-local correction so a returning lock resumes
     * where it left off. A re-capture is different: the anchor-local frame itself changed, so a
     * correction expressed in the OLD anchor frame is now meaningless and must be cleared.
     */
    fun reset() {
        correction = null
        coldStart = true
        lastSeq = 0f
        lastState = FusionState.WAITING_FOR_LOCK
        lastAlpha = -1f
        lastInlierRatio = -1f
        // snapsAccepted/snapsRejected intentionally NOT reset: they are session-lifetime eval counters
        // (see diagnostics()), and a re-capture mid-session is not a new session.
    }

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
         * **`D` here is the fixed CV↔GL axis-conversion matrix, not PoseFusion's persistent
         * correction.** `D` goes on ONE side, not both. Converting a transform between conventions
         * is normally the conjugation `D · m · D`, and that is what I wrote first. It is wrong here
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
     * @param confGlobal corroboration CONFIDENCE, not painting progress — see [CONF_FLOOR]'s doc for
     *        why that distinction matters and why this replaced the whole-design progress ratio.
     *        `matched / predicted` over the design features the current pose predicts are visible
     *        right now, in [0,1] but in practice capped well below 1 (see [CONF_FLOOR]). Raises
     *        smooth correction strength from [CONF_FLOOR] toward full as corroboration rises, which
     *        is the "the further along, the tighter it locks" behaviour — but per-frame, not
     *        monotonic across the mural's progress the way the name suggests. Never lowers it below
     *        the floor, so a bare wall still corrects on the PnP inlier ratio alone. Produced by
     *        `MobileGS::runRelocPass`, either from the bundled distortion-head model (shipped
     *        default) or a descriptor-similarity fallback — see `docs/TELEOLOGICAL_SLAM.md`.
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
            // Anchor-local correction such that backbone ∘ L == corrected at snap time. This relative
            // transform is frame-invariant under ARCore's global world-coordinate rewrites.
            val newLocal = PoseMath.multiply(PoseMath.rigidInverse(backbone), corrected)

            // Cold = first lock after (re)start/tracking-loss, no prior correction, or a fix that
            // diverges far from where we're currently drawing (the pocket case). Compare in the
            // CURRENT world frame by carrying the stored local correction through the live backbone.
            val applied = correction?.let { PoseMath.multiply(backbone, it) }
            val cold = coldStart || applied == null || diverged(applied, corrected)
            val highConf = inlierRatio >= COLD_SNAP_INLIER_RATIO && inliers >= COLD_SNAP_MIN_INLIERS

            correction = if (cold && highConf) {
                coldStart = false
                lastState = FusionState.COLD_SNAP
                lastAlpha = -1f          // a snap has no blend rate; -1 says so rather than 1.0
                newLocal // instant relock, stored relative to the backbone
            } else {
                val effConf = (CONF_FLOOR + (1f - CONF_FLOOR) * confGlobal.coerceIn(0f, 1f))
                val alpha = (BASE_ALPHA * inlierRatio * effConf).coerceIn(0f, 1f)
                lastState = FusionState.BLENDING
                lastAlpha = alpha
                blend(correction ?: identity(), newLocal, alpha)
            }
            snapsAccepted++
        } else if (isNew && correction != null) {
            // A relock reached fusion THIS tick and was refused by MIN_INLIER_RATIO (above), but a
            // standing correction from an earlier accepted relock already exists. Distinct from the
            // branch below: "an attempt arrived and was thrown away" is a different fault from
            // "nothing arrived", and the two look identical on every other channel — the overlay
            // just sits there either way. Conflating them as HOLDING is what let fusion silently
            // refuse every correction while the diagnostic overlay reported the healthy steady state.
            lastState = FusionState.RELOCK_REFUSED
        } else if (correction != null) {
            // A local correction stands but nothing new arrived this tick at all. It remains valid
            // under a global ARCore world rebase because the live backbone carries it into the
            // current frame.
            lastState = FusionState.HOLDING
        }
        lastSeq = seq
        // fused = backbone ∘ L, re-applied every frame (identity until the first trusted snap).
        return PoseMath.multiply(backbone, correction ?: identity())
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
     * "how far is it pulling the overlay" is the question a reader actually has. The correction is
     * anchor-local, so these magnitudes are invariant under ARCore world-frame rebasing. Millimetres
     * and degrees are both -1 when no correction stands.
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
