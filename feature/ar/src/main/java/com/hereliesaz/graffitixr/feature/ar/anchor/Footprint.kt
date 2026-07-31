package com.hereliesaz.graffitixr.feature.ar.anchor

import kotlin.math.abs
import kotlin.math.max

/**
 * The **footprint operator** Φ — see `docs/research/PAPER.md` §5.
 *
 * Maps a world point into the design's own normalized coordinates:
 *
 * ```
 * Φ(x) = ( (M⁻¹x)_x / h_w ,  (M⁻¹x)_y / h_h )
 * ```
 *
 * Inside the design, Φ lands in `[-1,+1]²`. Outside it, the magnitude says how far outside *in units
 * of the design's own size* — which is the quantity every downstream decision actually wants, and
 * the reason Φ is the single piece of machinery the hybrid relocalizer rests on. It is what lets the
 * system ask "is this feature *on* the artwork?", the question an appearance-only design could never
 * answer.
 *
 * **Φ discards the plane-normal component deliberately.** A point 0.5 m in front of the wall maps to
 * the same `(u,v)` as its foot on the wall. The partition is about *where on the design* a feature
 * sits, not how far off the surface it is; off-surface points are rejected earlier, by
 * [PlaneMarks.backProject]'s depth range.
 *
 * ## The scale trap
 *
 * `M` is the overlay's composed model matrix, and it is **not rigid** — `ArRenderer` folds the
 * user's pinch into it via `Matrix.scaleM(.., overlayScale, overlayScale, 1f)`. Inverting it with
 * [PoseMath.rigidInverse] (which transposes) is wrong by `s²`: at `overlayScale = 2` a feature
 * sitting exactly on the design edge comes out at `‖Φ‖∞ = 4`, is classified [Region.OUTSIDE], and
 * gets promoted into the backbone — the set defined as wall that never gets painted. Every feature
 * under the artwork would land there, which is precisely the corruption the partition exists to
 * prevent.
 *
 * Two safe ways to call this, both covered by tests:
 *
 *  - **Preferred.** Pass the *rigid* factor as [anchorModel] and fold the scale into the extents:
 *    `of(rigidAnchor, halfW * s, halfH * s, ...)`. Keeps Φ a pure rigid-frame operation and makes
 *    the scale dependency visible at the call site.
 *  - Pass the composed matrix and let [ofComposed] divide the scale out via
 *    [PoseMath.similarityInverse].
 */
object Footprint {

    /** Where a point sits relative to the design. */
    enum class Region {
        /** Inside the design — the corroboration set `F_in`. Expected to decay as it is painted. */
        INSIDE,

        /**
         * The discard band straddling the design's edge. Neither set.
         *
         * This region is easy to skip and should not be. Features within a hair of the boundary are
         * exactly the ones whose classification flips as the artist paints out to the edge, and a
         * *misclassified* feature is worse than a discarded one: a doomed feature admitted to the
         * backbone corrupts the thing the backbone exists to be.
         */
        BAND,

        /** Outside the design — the backbone set `F_out`. Expected to stay stable all session. */
        OUTSIDE,
    }

    /**
     * Normalized design coordinates of a world point, written into [out] and returned.
     *
     * @param anchorModel the design's **rigid** model matrix, column-major 4x4. If yours carries the
     *   overlay scale, use [ofComposed] instead — see the class docs.
     * @param halfW half-width of the design in metres, already multiplied by the overlay scale.
     * @param halfH half-height, likewise.
     * @param out a caller-supplied 2-element buffer. Φ runs once per feature per capture, so the
     *   overload exists to keep it out of the allocator.
     */
    fun of(
        anchorModel: FloatArray,
        halfW: Float, halfH: Float,
        x: Float, y: Float, z: Float,
        out: FloatArray = FloatArray(2),
    ): FloatArray = project(PoseMath.rigidInverse(anchorModel), halfW, halfH, x, y, z, out)

    /**
     * As [of], but for a model matrix that carries a uniform in-plane scale (the composed overlay
     * transform). The scale is divided out of the inverse AND out of the extents, so [halfW]/[halfH]
     * here are the design's **unscaled** half-extents.
     */
    fun ofComposed(
        composedModel: FloatArray,
        halfW: Float, halfH: Float,
        x: Float, y: Float, z: Float,
        out: FloatArray = FloatArray(2),
    ): FloatArray = project(PoseMath.similarityInverse(composedModel), halfW, halfH, x, y, z, out)

    /** Shared core: apply an already-inverted transform, then normalize by the half-extents. */
    private fun project(
        inverse: FloatArray,
        halfW: Float, halfH: Float,
        x: Float, y: Float, z: Float,
        out: FloatArray,
    ): FloatArray {
        require(halfW > 1e-6f && halfH > 1e-6f) { "degenerate design extents: $halfW x $halfH" }
        // Local X and Y only — Z is the plane normal and Φ discards it by construction.
        val lx = inverse[0] * x + inverse[4] * y + inverse[8] * z + inverse[12]
        val ly = inverse[1] * x + inverse[5] * y + inverse[9] * z + inverse[13]
        out[0] = lx / halfW
        out[1] = ly / halfH
        return out
    }

    /**
     * True when [uv] lies within the design rectangle, dilated by [margin] (in Φ units, so 0.1 means
     * "a tenth of the design's half-extent past the edge").
     */
    fun isInside(uv: FloatArray, margin: Float = 0f): Boolean =
        abs(uv[0]) <= 1f + margin && abs(uv[1]) <= 1f + margin

    /**
     * Chebyshev distance from the design rectangle: 0 inside, growing outside.
     *
     * Chebyshev rather than Euclidean because the design is a rectangle, not a disc — a point 0.2
     * past the right edge and a point 0.2 past the top edge are equally "just outside", and the
     * corner should not be treated as further away than the sides.
     */
    fun outsideDistance(uv: FloatArray): Float =
        max(0f, max(abs(uv[0]) - 1f, abs(uv[1]) - 1f))

    /**
     * Partition a point into the backbone set, the corroboration set, or the discard band.
     *
     * [innerMargin] shrinks the inside test and [outerMargin] grows the outside test; the gap
     * between them is the band. Requiring `innerMargin < outerMargin` is not a formality — inverted
     * margins would silently produce an empty band and hand boundary features straight to the
     * backbone, which is the failure the band exists to prevent.
     */
    fun classify(uv: FloatArray, innerMargin: Float, outerMargin: Float): Region {
        require(innerMargin >= 0f && outerMargin > innerMargin) {
            "margins must satisfy 0 <= inner ($innerMargin) < outer ($outerMargin)"
        }
        val d = max(abs(uv[0]), abs(uv[1]))
        return when {
            d <= 1f - innerMargin -> Region.INSIDE
            d >= 1f + outerMargin -> Region.OUTSIDE
            else -> Region.BAND
        }
    }
}
