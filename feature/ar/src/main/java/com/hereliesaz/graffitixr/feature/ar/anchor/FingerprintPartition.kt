package com.hereliesaz.graffitixr.feature.ar.anchor

import com.hereliesaz.graffitixr.common.model.Fingerprint

/**
 * `IMPLEMENTATION.md` **Phase 2** — tag every fingerprint point with its region relative to the
 * design's projected footprint.
 *
 * This is the join between Φ ([Footprint], Phase 1) and the stored map ([Fingerprint], `core/common`).
 * It lives in `feature/ar` and not on `Fingerprint` itself because the module graph runs
 * `feature/ar → core/common`: the data model cannot see [Footprint]. So `Fingerprint` carries the
 * bytes and the invariants, and the geometry that produces them lives here.
 *
 * **What the partition is for**, from `PAPER.md` §5: the backbone `F_out` sits outside the design
 * and survives the whole job, so it is what the reloc PnP localizes against with no prior. The
 * corroboration set `F_in` sits inside the design — it is the work surface, it decays as the artist
 * paints, and it is only ever consulted *after* a pose exists. Mixing them is what makes accuracy
 * degrade with task progress, which is the failure the whole programme is about.
 *
 * The [Footprint.Region.BAND] straddling the edge goes into neither set. That is deliberate and is
 * the easiest part of this to skip: features within a hair of the boundary are exactly the ones
 * whose classification flips as the artist paints out to the edge, and a *misclassified* feature is
 * worse than a discarded one — a doomed feature admitted to the backbone corrupts the thing the
 * backbone exists to be.
 */
object FingerprintPartition {

    /**
     * Default margins, as fractions of the design's half-extent, in normalized design coordinates.
     *
     * Asymmetric on purpose. The inner margin only has to exclude points whose *measured* position
     * is uncertain; the outer one additionally absorbs the artist overshooting the design's edge,
     * which they routinely do and which would silently convert backbone into painted-over.
     *
     * These are starting values, not tuned ones — `EVALUATION.md` E8 is the experiment that sets
     * them, and `PARAMETERS.md` is where they are recorded.
     */
    const val DEFAULT_INNER_MARGIN = 0.04f
    const val DEFAULT_OUTER_MARGIN = 0.10f

    /**
     * The design's placement at capture, in **world** space, as the renderer knows it.
     *
     * One object rather than three loose parameters because the three are only meaningful together,
     * and because "no partition" then reads as a deliberate `null` at the call site instead of three
     * numbers somebody forgot. Absent is safe here — an unpartitioned fingerprint is the legacy
     * all-backbone case, i.e. exactly today's behaviour — which is why the builder is allowed to
     * default it away, unlike `rotationDeg`, where the safe value does not exist.
     *
     * @param rigidModel the design's world pose **without** the overlay scale.
     * @param halfW the design's **effective** (scale-included) half-width in metres.
     */
    class DesignFootprint(
        val rigidModel: FloatArray,
        val halfW: Float,
        val halfH: Float,
    ) {
        /** True when this describes a design with real extents; a zero/negative one cannot be Φ'd. */
        val isUsable: Boolean
            get() = rigidModel.size == 16 && halfW > 1e-6f && halfH > 1e-6f

        /**
         * The same footprint with its model expressed in a capture camera frame, given that frame's
         * world→camera view.
         *
         * This is the whole frame story in one line. [Fingerprint.points3d] are camera-frame, the
         * renderer's design pose is world-frame, and Φ requires both in one frame. Pre-multiplying
         * the view is exact rather than approximate:
         *
         * ```
         * inv(V·M) · p_cam  ==  inv(M) · inv(V) · p_cam  ==  inv(M) · p_world
         * ```
         *
         * and rigid ∘ rigid stays rigid, so [Footprint.inverseOfRigid] remains applicable. Doing it
         * this way — rather than mapping every point to world — also costs one 4x4 multiply instead
         * of one per feature, and leaves a single matrix that can be stored for a later reclassify.
         *
         * [PoseMath.multiply] rather than `android.opengl.Matrix.multiplyMM`: identical arithmetic,
         * but it runs under a plain JVM unit test. The framework version is a stub off-device, and a
         * stubbed matrix multiply does not fail — it silently yields zeros, which invert to garbage
         * and classify everything as INSIDE. This function's whole correctness claim is a frame
         * reconciliation, so it has to be the kind of thing a test can actually exercise.
         */
        fun inFrameOf(view: FloatArray): DesignFootprint =
            DesignFootprint(PoseMath.multiply(view, rigidModel), halfW, halfH)
    }

    /**
     * Classify [points] — a flat `[x,y,z,...]` list — into one region byte per point.
     *
     * [points] and [designModel] must be expressed in the **same frame**, whichever that is. Φ is
     * frame-agnostic: it only ever computes `inv(designModel) · p`, so world/world and camera/camera
     * are both correct and world/camera is silently, plausibly wrong. Use
     * [DesignFootprint.inFrameOf] to reconcile them rather than doing it by hand at the call site.
     *
     * @param designModel the design's model matrix. Pass [composed] = true when it carries the
     *   overlay's uniform in-plane scale, in which case [halfW]/[halfH] must be the design's
     *   **unscaled** half-extents; otherwise pass the **scaled** ones. Getting this pair wrong is
     *   the highest-probability bug here — both combinations compile and both produce plausible
     *   numbers — which is why `Footprint` exposes them as two named entry points rather than a
     *   boolean, and why `FingerprintPartitionTest` covers a scaled anchor explicitly.
     */
    fun classify(
        points: FloatArray,
        designModel: FloatArray,
        halfW: Float,
        halfH: Float,
        composed: Boolean = false,
        innerMargin: Float = DEFAULT_INNER_MARGIN,
        outerMargin: Float = DEFAULT_OUTER_MARGIN,
    ): ByteArray {
        val count = points.size / 3
        if (count == 0) return ByteArray(0)
        // Hoist the inverse: Footprint.of/ofComposed each allocate a FloatArray(16) per call, and
        // ofComposed also does a sqrt to recover a scale that is constant across the capture. Over a
        // 1500-feature capture that dominates everything else this function does.
        val inv = if (composed) Footprint.inverseOfComposed(designModel)
        else Footprint.inverseOfRigid(designModel)
        val uv = FloatArray(2)
        val out = ByteArray(count)
        for (i in 0 until count) {
            val o = i * 3
            Footprint.ofInverted(inv, halfW, halfH, points[o], points[o + 1], points[o + 2], uv)
            out[i] = Footprint.classify(uv, innerMargin, outerMargin).ordinal.toByte()
        }
        return out
    }

    /**
     * Recompute [Fingerprint.regions] against a new design size, returning an updated copy.
     *
     * The artist pinching the design mid-session is normal, so the response is to reclassify the
     * stored 3D points — a cheap pure-Kotlin pass — rather than force a re-capture.
     *
     * Returns the receiver unchanged when there is nothing to do: no points to classify. Note it
     * does **not** short-circuit on [Fingerprint.isPartitionStale] being false, because a caller may
     * legitimately want to partition a previously-unpartitioned (legacy) fingerprint, for which
     * "stale" is defined as false.
     *
     * [designModel] must be **rigid**, with the overlay scale folded into [halfW]/[halfH] — the
     * convention `Footprint` calls preferred, and the only one that can be stored and replayed. A
     * composed matrix has no place here: `Matrix.scaleM(m, 0, s, s, 1f)` scales X and Y but not Z,
     * so it is not the uniform similarity [Footprint.inverseOfComposed] assumes, and the stored
     * [Fingerprint.captureHalfW] is defined as scale-included regardless.
     */
    fun reclassify(
        fingerprint: Fingerprint,
        designModel: FloatArray,
        halfW: Float,
        halfH: Float,
        innerMargin: Float = DEFAULT_INNER_MARGIN,
        outerMargin: Float = DEFAULT_OUTER_MARGIN,
    ): Fingerprint {
        if (fingerprint.points3d.isEmpty()) return fingerprint
        val regions = classify(
            fingerprint.points3d.toFloatArray(), designModel, halfW, halfH,
            composed = false, innerMargin = innerMargin, outerMargin = outerMargin,
        )
        return fingerprint.copy(
            regions = regions,
            captureHalfW = halfW,
            captureHalfH = halfH,
            // Keep the model that produced these bytes with them. Without it the next reclassify has
            // to be handed a matrix again, and after a project reload there is no correct one to
            // hand it — the capture's world frame is gone with the ARCore session.
            captureDesignModel = designModel.toList(),
        )
    }

    /**
     * Reclassify against the design model the fingerprint already carries — the reload-safe form.
     *
     * This is the one a resize handler should call. It cannot be given a matrix in the wrong frame
     * because it is not given one at all: [Fingerprint.captureDesignModel] was pre-multiplied into
     * the capture frame at capture, which is the frame [Fingerprint.points3d] is in.
     *
     * Returns the receiver unchanged when there is no stored model (a pre-Phase-2 or depth-path
     * fingerprint). That is a refusal, not a silent success: reclassifying against a guessed frame
     * would produce a full, plausible, wrong partition, and the legacy all-backbone reading is the
     * behaviour `main` already has.
     */
    fun reclassify(fingerprint: Fingerprint, halfW: Float, halfH: Float): Fingerprint {
        val model = fingerprint.captureDesignModel
        if (model.size != 16) return fingerprint
        return reclassify(fingerprint, model.toFloatArray(), halfW, halfH)
    }

    /**
     * How many points fall in the backbone set `F_out`.
     *
     * An **unpartitioned** fingerprint counts as all-backbone, matching the legacy reading in
     * [Fingerprint.regions] — a pre-Phase-2 map must keep relocalizing, not be excluded wholesale.
     */
    fun backboneCount(fingerprint: Fingerprint): Int {
        if (fingerprint.regions.isEmpty()) return fingerprint.points3d.size / 3
        return backboneCount(fingerprint.regions)
    }

    /**
     * How many of [regions] are backbone. Note this is the *raw* count and has no legacy reading —
     * an empty array is 0 here, not "all of them", because there is no point list in scope to say
     * how many "all" would be. Callers holding a fingerprint should use the overload above.
     *
     * Split out so the capture-time `F_out` floor and the diagnostic count are literally the same
     * arithmetic. `count { it != INSIDE }` and `count { it == OUTSIDE }` differ precisely on the
     * BAND, and having that decision written twice is how the two drift apart.
     */
    fun backboneCount(regions: ByteArray): Int {
        val outside = Footprint.Region.OUTSIDE.ordinal.toByte()
        return regions.count { it == outside }
    }
}
