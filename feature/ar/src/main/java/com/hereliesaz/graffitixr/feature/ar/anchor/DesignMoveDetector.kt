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
 * frame that reported no movement makes a slow drag invisible: 15 cm over three seconds is 0.08 mm
 * per frame at 60 fps, under any sane threshold on every single frame, so the delta never
 * accumulates and the design ends up 15 cm from the footprint Φ was computed against. Fast drags
 * fired and the careful final placement did not — the worst possible split, since the last thing an
 * artist does before painting is nudge the design into position.
 *
 * **Its inputs must be drift-immune.** The obvious candidates are not. The composed model matrix
 * carries `R_anchorᵀ`, and so does the marks-centering offset (`X_world · (R_anchor · markLocal)`);
 * `anchorMatrix` is the *fused* pose, re-blended on every reloc snap, so a threshold on either fires
 * with the phone sitting still. Only quantities set from gestures qualify — pan, spin, and the
 * overlay extents. Two successive attempts at this shipped a drift-coupled input.
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

    /**
     * True when [panX]/[panY] (metres), [rotDeg] (degrees) or the effective half-extents (metres)
     * have moved past their thresholds **since the last call that returned true**.
     *
     * Records the new values only when it returns true. That asymmetry is the whole point: see the
     * class docs for what recording unconditionally does to a slow drag.
     */
    fun moved(panX: Float, panY: Float, rotDeg: Float, halfW: Float, halfH: Float): Boolean {
        val moved = !(abs(panX - this.panX) <= panEpsM &&
            abs(panY - this.panY) <= panEpsM &&
            abs(rotDeg - this.rotDeg) <= rotEpsDeg &&
            abs(halfW - this.halfW) <= extentEpsM &&
            abs(halfH - this.halfH) <= extentEpsM)
        if (moved) {
            this.panX = panX; this.panY = panY; this.rotDeg = rotDeg
            this.halfW = halfW; this.halfH = halfH
        }
        return moved
    }

    /** Forget the last publish, so the next call reports movement. For session teardown. */
    fun reset() {
        panX = Float.NaN; panY = Float.NaN; rotDeg = Float.NaN
        halfW = Float.NaN; halfH = Float.NaN
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
