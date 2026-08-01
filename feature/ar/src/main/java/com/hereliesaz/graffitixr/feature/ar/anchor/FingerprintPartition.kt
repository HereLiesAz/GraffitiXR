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
     * Classify [pointsCam] — a flat `[x,y,z,...]` list in the same world frame [anchorModel] is
     * expressed in — into one region byte per point.
     *
     * @param anchorModel the design's model matrix. Pass [composed] = true when it carries the
     *   overlay's uniform in-plane scale, in which case [halfW]/[halfH] must be the design's
     *   **unscaled** half-extents; otherwise pass the **scaled** ones. Getting this pair wrong is
     *   the highest-probability bug here — both combinations compile and both produce plausible
     *   numbers — which is why `Footprint` exposes them as two named entry points rather than a
     *   boolean, and why `FingerprintPartitionTest` covers a scaled anchor explicitly.
     */
    fun classify(
        pointsCam: FloatArray,
        anchorModel: FloatArray,
        halfW: Float,
        halfH: Float,
        composed: Boolean = false,
        innerMargin: Float = DEFAULT_INNER_MARGIN,
        outerMargin: Float = DEFAULT_OUTER_MARGIN,
    ): ByteArray {
        val count = pointsCam.size / 3
        if (count == 0) return ByteArray(0)
        // Hoist the inverse: Footprint.of/ofComposed each allocate a FloatArray(16) per call, and
        // ofComposed also does a sqrt to recover a scale that is constant across the capture. Over a
        // 1500-feature capture that dominates everything else this function does.
        val inv = if (composed) Footprint.inverseOfComposed(anchorModel)
        else Footprint.inverseOfRigid(anchorModel)
        val uv = FloatArray(2)
        val out = ByteArray(count)
        for (i in 0 until count) {
            val o = i * 3
            Footprint.ofInverted(inv, halfW, halfH, pointsCam[o], pointsCam[o + 1], pointsCam[o + 2], uv)
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
     */
    fun reclassify(
        fingerprint: Fingerprint,
        anchorModel: FloatArray,
        halfW: Float,
        halfH: Float,
        composed: Boolean = false,
        innerMargin: Float = DEFAULT_INNER_MARGIN,
        outerMargin: Float = DEFAULT_OUTER_MARGIN,
    ): Fingerprint {
        if (fingerprint.points3d.isEmpty()) return fingerprint
        val regions = classify(
            fingerprint.points3d.toFloatArray(), anchorModel, halfW, halfH,
            composed, innerMargin, outerMargin,
        )
        return fingerprint.copy(
            regions = regions,
            captureHalfW = halfW,
            captureHalfH = halfH,
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
        val outside = Footprint.Region.OUTSIDE.ordinal.toByte()
        return fingerprint.regions.count { it == outside }
    }
}
