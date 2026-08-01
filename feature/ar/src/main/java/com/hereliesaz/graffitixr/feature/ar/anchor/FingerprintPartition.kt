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
     * Minimum backbone size for a partitioned fingerprint to be able to relocalize at all
     * (`IMPLEMENTATION.md` 2.8).
     *
     * `F_out` is what the reloc PnP solves from with no prior. If the artwork covers the whole
     * visible wall there is nothing outside it and the target will never lock — `PAPER.md` states
     * this as a limit of the domain of applicability, so the implementation's job is to say so
     * plainly rather than let the artist discover it as mysterious tracking failure an hour in.
     *
     * `40` is the pre-registered `PARAMETERS.md` §4 prior: 5× the native PnP correspondence floor of
     * 8, leaving room for the fact that only a fraction of stored features match in any one live
     * frame. A prior, not a measurement — `EVALUATION.md` E8 sets it, and both failure modes are
     * real: too high cries wolf, too low stays silent on a target that cannot work.
     *
     * Checked where the partition is computed, which is when the artwork is placed — **not** at
     * capture. Target creation is what establishes the anchor the artwork sits on, so at capture
     * there is no footprint and therefore no such thing as too little wall around it.
     */
    const val MIN_BACKBONE = 40

    /**
     * Where the artwork sits, expressed **relative to the fingerprint anchor** — `A⁻¹ · M_design` —
     * together with its effective, scale-included half-extents in metres.
     *
     * **Anchor-relative, not world.** The obvious choice is the design's world pose, and it is
     * wrong: ARCore's world origin does not survive a session, and within one it drifts and is
     * corrected by relocalization, so a world pose recorded at one moment does not describe the same
     * physical place at another. The anchor does survive both — it is the frame
     * [Fingerprint.markCenterLocal] already uses for exactly this reason — so pairing it with
     * [Fingerprint.captureAnchorCam] gives a composition with no world frame in it at all.
     *
     * One object rather than three loose parameters because the three are only meaningful together,
     * and because "no design placed" then reads as a deliberate `null` at the call site instead of
     * three numbers somebody forgot. Absent is safe: an unpartitioned fingerprint is the legacy
     * all-backbone case, which is exactly the behaviour before Phase 2.
     *
     * @param rigidModelAnchorLocal the design's pose relative to the anchor, **without** the overlay
     *   scale — that is folded into the extents instead, because `Matrix.scaleM(m, 0, s, s, 1f)`
     *   scales X and Y but not Z and is therefore not the uniform similarity an inverse-with-scale
     *   would assume. See `Footprint`'s "scale trap".
     * @param halfW the design's **effective** (scale-included) half-width in metres.
     */
    class DesignFootprint(
        val rigidModelAnchorLocal: FloatArray,
        val halfW: Float,
        val halfH: Float,
    ) {
        /** True when this describes a design with real extents; a zero/negative one cannot be Φ'd. */
        val isUsable: Boolean
            get() = rigidModelAnchorLocal.size == 16 && halfW > 1e-6f && halfH > 1e-6f
    }

    /**
     * Classify [points] — a flat `[x,y,z,...]` list — into one region byte per point.
     *
     * [points] and [designModel] must be expressed in the **same frame**, whichever that is. Φ is
     * frame-agnostic: it only ever computes `inv(designModel) · p`, so world/world and camera/camera
     * are both correct and world/camera is silently, plausibly wrong. Prefer [partition], which
     * composes the frames for you, over reconciling them by hand at the call site.
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
     * Partition (or repartition) [fingerprint] against where the artwork currently sits, returning
     * an updated copy.
     *
     * This is the only entry point production should use, and it takes no matrix in the points'
     * frame — so no caller can supply one in the wrong frame. It composes the two durable factors:
     *
     * ```
     * design_in_points_frame  =  fingerprint.captureAnchorCam · design.rigidModelAnchorLocal
     * ```
     *
     * the first frozen at capture, the second read live off the renderer, and neither expressed in
     * a world frame that drift or a session boundary could invalidate.
     *
     * **This runs when the artwork lands, not (only) at capture.** Target creation is what
     * *establishes* the anchor the artwork will sit on, so at first capture there is no design to
     * partition against and `regions` is legitimately empty. Deferring the partition to the moment
     * the design is placed or resized is not a fallback — it is the only point in the flow where the
     * question Φ asks ("is this feature under the artwork?") has an answer.
     *
     * Returns the **receiver itself** — identity-comparable, so a caller can cheaply skip a
     * re-push — when it cannot honestly partition: no 3D points, no [Fingerprint.captureAnchorCam]
     * (a pre-Phase-2 or depth-path fingerprint), or a design with no usable extents. Each is a
     * refusal rather than a guess: partitioning against an assumed frame produces a complete,
     * plausible, wrong answer, whereas leaving it unpartitioned reproduces `main` exactly.
     */
    fun partition(
        fingerprint: Fingerprint,
        design: DesignFootprint,
        innerMargin: Float = DEFAULT_INNER_MARGIN,
        outerMargin: Float = DEFAULT_OUTER_MARGIN,
    ): Fingerprint {
        if (fingerprint.points3d.isEmpty()) return fingerprint
        if (fingerprint.captureAnchorCam.size != 16) return fingerprint
        if (!design.isUsable) return fingerprint
        val designInPointFrame = PoseMath.multiply(
            fingerprint.captureAnchorCam.toFloatArray(), design.rigidModelAnchorLocal,
        )
        return fingerprint.copy(
            regions = classify(
                fingerprint.points3d.toFloatArray(), designInPointFrame, design.halfW, design.halfH,
                composed = false, innerMargin = innerMargin, outerMargin = outerMargin,
            ),
            captureHalfW = design.halfW,
            captureHalfH = design.halfH,
        )
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
