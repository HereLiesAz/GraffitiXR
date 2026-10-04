// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/StandalonePoseStabilizer.kt
package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.graffitixr.feature.ar.anchor.PoseFusion

/**
 * A temporal low-pass for the standalone (non-ARCore) render pose.
 *
 * The standalone path emits the raw per-frame KPM view matrix straight to the renderer, so any
 * per-frame KPM jitter inside the (deliberately loose) acceptance envelope shimmers the overlay.
 * This blends each new pose toward the previous emitted one, damping that jitter — but only while
 * the two are close: a legitimately large frame-to-frame move (a fast pan, a reacquisition after a
 * dropout) exceeds [PoseFusion.diverged]'s thresholds and passes through unsmoothed, so the overlay
 * never lags real motion.
 *
 * It operates on the column-major GL view matrix directly via the same rigid-pose primitives
 * [PoseFusion] uses (translation lerp + quaternion nlerp), and holds no frame-dependent state beyond
 * the last emitted pose, which [reset] clears on tracking loss or a session rebuild.
 *
 * This is a stabilizer, not a drift corrector: it cannot remove accumulated KPM error (nothing here
 * observes the wall independently). The reloc-corroboration fusion in
 * [SphereSlamStandaloneTrackingAnalyzer] does that; this only smooths what that produces.
 */
internal class StandalonePoseStabilizer(
    private val alpha: Float = DEFAULT_ALPHA,
) {
    private var last: FloatArray? = null

    /**
     * Returns the pose to render for [raw]: [raw] itself on the first frame or after a large move
     * (a snap), otherwise [raw] blended toward the previous emitted pose by [alpha]. The returned
     * array is retained as the next frame's baseline, so callers must not mutate it in place.
     */
    fun stabilize(raw: FloatArray): FloatArray {
        require(raw.size == 16)
        val prev = last
        val out = if (prev == null || PoseFusion.diverged(prev, raw)) {
            raw.copyOf()
        } else {
            PoseFusion.blend(prev, raw, alpha)
        }
        last = out
        return out
    }

    /** Forget the last pose so the next [stabilize] snaps instead of blending across a discontinuity. */
    fun reset() {
        last = null
    }

    companion object {
        /**
         * Weight toward the newest pose each frame. 0.5 halves per-frame jitter while keeping the
         * emitted pose within half a frame's motion of the truth — responsive enough that the lag is
         * imperceptible at camera frame rates, firm enough to kill shimmer.
         */
        const val DEFAULT_ALPHA = 0.5f
    }
}
