package com.hereliesaz.graffitixr.feature.ar.anchor

import kotlin.math.abs

/**
 * Decides when the artist has moved or resized the artwork enough to be worth repartitioning the
 * fingerprint (`IMPLEMENTATION.md` 2.2).
 *
 * Extracted from `ArRenderer` for one reason: this logic has now been wrong twice, in two different
 * ways, and both times it was unreachable from a test because it lived inside a `GLSurfaceView`
 * frame callback. Here it is ordinary Kotlin with no Android dependency, so the two failures below
 * are pinned by `DesignMoveDetectorTest` rather than by a comment asking the reader to be careful.
 *
 * ## The two failures
 *
 * **It compares against the last PUBLISH, not the last frame.** Advancing the remembered values on a
 * frame that reported no movement makes a slow drag invisible: 15 cm over three seconds is 0.83 mm
 * per frame at 60 fps, under the 1 mm threshold on every single frame, so the delta never
 * accumulates and the design ends up 15 cm from the footprint Φ was computed against. Fast drags
 * fired and the careful final placement did not — the worst possible split, since the last thing an
 * artist does before painting is nudge the design into position.
 *
 * **Its inputs must be drift-immune.** The obvious candidates are not. The composed model matrix
 * carries `R_anchorᵀ`, and so does the marks-centering offset (`X_world · (R_anchor · markLocal)`);
 * `anchorMatrix` is the *fused* pose, re-blended on every reloc snap, so a threshold on either fires
 * with the phone sitting still. Two successive attempts at this shipped a drift-coupled input.
 *
 * Pan and spin are set from gestures. The extents are `extentHalfW * overlayScale`, of which only
 * the scale is a gesture — `extentHalfW` comes from a screen fit — but the fit is one-shot per
 * arming rather than per frame, so it is a step function, not a drift signal. That is the actual
 * reason it is safe, and it is worth stating precisely in a class whose whole history is a
 * drift-immunity argument being wrong.
 *
 * **And it must react to the anchor being REPLACED.** Removing the marks offset removed the only
 * input that moved when a re-capture established a new anchor — so the trigger went quiet on
 * exactly the path that most needs it, and the new fingerprint got partitioned against the previous
 * anchor's design pose. [anchorGeneration] restores that as a discrete counter, carrying the signal
 * with none of the drift. Note it also advances on session TEARDOWN, not only on establishment — so
 * the renderer must be stopped before teardown clears the partition state, or this fires on the way
 * down and republishes a footprint belonging to the session being destroyed.
 */
class DesignMoveDetector(
    private val panEpsM: Float = DEFAULT_PAN_EPS_M,
    private val rotEpsDeg: Float = DEFAULT_ROT_EPS_DEG,
    private val extentEpsM: Float = DEFAULT_EXTENT_EPS_M,
) {
    // NaN until the first publish, so the first call always reports movement — which is correct:
    // there is no previously-published footprint for the partition to already match.
    private var panX = Float.NaN
    private var panY = Float.NaN
    private var rotDeg = Float.NaN
    private var halfW = Float.NaN
    private var halfH = Float.NaN
    // -1 rather than 0: 0 is a real generation (no anchor established yet), so it cannot double as
    // "nothing published". Same argument as every other sentinel in this codebase.
    private var anchorGeneration = -1

    /**
     * True when [panX]/[panY] (metres), [rotDeg] (degrees) or the effective half-extents (metres)
     * have moved past their thresholds **since the last call that returned true**, or when
     * [anchorGeneration] differs at all — an anchor replacement changes the design's anchor-relative
     * pose outright, so there is no threshold to apply.
     *
     * Records the new values only when it returns true. That asymmetry is the whole point: see the
     * class docs for what recording unconditionally does to a slow drag.
     */
    fun moved(
        panX: Float,
        panY: Float,
        rotDeg: Float,
        halfW: Float,
        halfH: Float,
        anchorGeneration: Int,
    ): Boolean {
        val moved = anchorGeneration != this.anchorGeneration ||
            !(abs(panX - this.panX) <= panEpsM &&
                abs(panY - this.panY) <= panEpsM &&
                abs(rotDeg - this.rotDeg) <= rotEpsDeg &&
                abs(halfW - this.halfW) <= extentEpsM &&
                abs(halfH - this.halfH) <= extentEpsM)
        if (moved) {
            this.panX = panX; this.panY = panY; this.rotDeg = rotDeg
            this.halfW = halfW; this.halfH = halfH
            this.anchorGeneration = anchorGeneration
        }
        return moved
    }

    /** Forget the last publish, so the next call reports movement. */
    fun reset() {
        panX = Float.NaN; panY = Float.NaN; rotDeg = Float.NaN
        halfW = Float.NaN; halfH = Float.NaN
        anchorGeneration = -1
    }

    companion object {
        /**
         * A millimetre of pan or extent, and a tenth of a degree of spin. Below these a "change" is
         * float noise in the compose chain, and firing on it repartitions the fingerprint, replaces
         * the native map and rewrites the project file every frame the artist rests a finger on the
         * screen. Guesses with stated reasoning, recorded in `PARAMETERS.md` §6; no experiment sets
         * them, so if repartition cost or missed repartitions ever show up in a run, start here.
         */
        const val DEFAULT_PAN_EPS_M = 1e-3f
        const val DEFAULT_ROT_EPS_DEG = 0.1f
        const val DEFAULT_EXTENT_EPS_M = 1e-3f
    }
}
