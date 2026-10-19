package com.hereliesaz.graffitixr.feature.ar.anchor

/**
 * Picks WHICH of the two anchor-correction sources drives [PoseFusion] on a render frame.
 *
 * There are two: the timestamp-aligned KPM sidecar ("hybrid", needs a metric KPM page) and the
 * MobileGS PnP reloc path (needs `captureAnchorCam`). Both write the one standing anchor-local
 * correction. Hybrid observations are asynchronous, so most frames have none; before this arbiter a
 * frame with no accepted observation fell straight through to the reloc path, and when BOTH sources
 * existed the correction was steered by one on one frame and by the other on the next — two
 * estimators of the same quantity with different latency and bias, alternating.
 *
 * ## The rule (one, deterministic, a function of the inputs and one timestamp)
 *
 *  1. A hybrid observation accepted this frame is applied ([Source.HYBRID]).
 *  2. Otherwise, **while hybrid is fresh** — it was accepted within [HYBRID_FRESH_WINDOW_NS] — the
 *     standing correction is **held** ([Source.HOLD]). The reloc path is not consulted, even if it
 *     has a new result, and **even if the KPM page has just been unloaded**: the window, not the
 *     page's presence, decides when the other estimator may take over.
 *  3. Otherwise (hybrid never accepted since the last reset, or silent for the whole window) reloc
 *     drives, if there is a capture pose and the reloc path is not gated ([Source.RELOC]); with only
 *     a hybrid page ready the correction is held ([Source.HOLD]); with neither source the raw
 *     backbone is used ([Source.NONE]).
 *
 * **Paint goes through.** The KPM page is a photo of the wall as it was, and every coat of paint takes
 * it further from the wall as it is; reloc locks that stand on paint are the teleological
 * correction, getting better as the page gets worse. So a paint-backed reloc lock ([relocPaintBacked])
 * drives when hybrid is not holding well: silent past the window, or its last accepted observation
 * below [HYBRID_DEGRADED_CONF]. Once paint has driven, it keeps the anchor the way hybrid does: held
 * through frames without a paint-backed lock, until it too has been silent for the window, and a
 * hybrid acceptance in between does not take it back. Still one source at a time.
 *
 * **Reloc gate.** While a re-capture is replacing the wall doodle (`relocGated`) the reloc
 * result and `captureAnchorCam` describe the PREVIOUS wall. The reloc path is then unusable no matter
 * what else is true — [Source.RELOC] is never returned — so a later solve cannot cold-snap the new
 * anchor onto the old wall. Hybrid still works (its page is independent of the doodle).
 *
 * So the handover is one-way and timed: reloc until hybrid first accepts, hybrid while it keeps
 * accepting, reloc again only after hybrid has been unavailable for the full window. No frame-by-frame
 * flip is possible while hybrid keeps producing within the window.
 *
 * Pure and stateful only in the last accepted timestamp; the caller owns clock reads (frame time).
 */
class CorrectionSourceArbiter {
    enum class Source { HYBRID, HOLD, RELOC, NONE }

    private var lastHybridAcceptedNs = NEVER
    private var lastHybridConfidence = 1f
    private var lastPaintRelocNs = NEVER

    /** Forget the hybrid history — a new anchor (re-capture) starts from "reloc until hybrid accepts". */
    fun reset() {
        lastHybridAcceptedNs = NEVER
        lastHybridConfidence = 1f
        lastPaintRelocNs = NEVER
    }

    private fun paintFresh(nowNs: Long): Boolean =
        lastPaintRelocNs != NEVER && nowNs - lastPaintRelocNs in 0..HYBRID_FRESH_WINDOW_NS

    /** True while the last accepted hybrid observation is within [HYBRID_FRESH_WINDOW_NS] of [nowNs]. */
    fun hybridFresh(nowNs: Long): Boolean =
        lastHybridAcceptedNs != NEVER && nowNs - lastHybridAcceptedNs in 0..HYBRID_FRESH_WINDOW_NS

    /**
     * @param nowNs frame timestamp (ns)
     * @param hybridAccepted a hybrid observation passed its quality gates this frame
     * @param hybridFrameReady a metric KPM page is loaded and could produce observations
     * @param hasRelocCapturePose `captureAnchorCam` exists (the reloc path can compose a correction)
     * @param relocGated the wall doodle is being replaced (a re-capture's anchor exists but its
     *   new doodle and `captureAnchorCam` are not both installed yet): reloc must not drive
     * @param relocPaintBacked the current reloc lock is fresh and stands on paint
     * @param hybridConfidence the confidence of this frame's accepted hybrid observation, `[0,1]`
     */
    fun select(
        nowNs: Long,
        hybridAccepted: Boolean,
        hybridFrameReady: Boolean,
        hasRelocCapturePose: Boolean,
        relocGated: Boolean = false,
        relocPaintBacked: Boolean = false,
        hybridConfidence: Float = 1f,
    ): Source {
        if (hybridAccepted) lastHybridConfidence = hybridConfidence
        val relocUsable = hasRelocCapturePose && !relocGated
        if (!relocUsable) lastPaintRelocNs = NEVER
        val hybridHolding = hybridFresh(nowNs) || hybridAccepted
        if (
            relocUsable && relocPaintBacked &&
            (paintFresh(nowNs) || !hybridHolding || lastHybridConfidence < HYBRID_DEGRADED_CONF)
        ) {
            if (hybridAccepted) lastHybridAcceptedNs = nowNs
            lastPaintRelocNs = nowNs
            return Source.RELOC
        }
        if (paintFresh(nowNs)) {
            if (hybridAccepted) lastHybridAcceptedNs = nowNs
            return Source.HOLD
        }
        if (hybridAccepted) {
            lastHybridAcceptedNs = nowNs
            return Source.HYBRID
        }
        if (hybridFresh(nowNs)) return Source.HOLD
        return when {
            hasRelocCapturePose && !relocGated -> Source.RELOC
            hybridFrameReady -> Source.HOLD
            else -> Source.NONE
        }
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE

        /**
         * How long hybrid may stay silent before reloc takes over again. KPM matches arrive well under
         * once a second when the page is in view; 3 s tolerates a few missed or rejected
         * observations (blur, a pan away) without handing a good correction to the other estimator,
         * yet recovers within a few seconds if the page is truly lost. Unmeasured — a candidate for
         * the same eval sweep as the other fusion constants.
         */
        const val HYBRID_FRESH_WINDOW_NS = 3_000_000_000L

        /**
         * Below this, an accepted hybrid observation no longer keeps a paint-backed lock out. Hybrid
         * acceptance already passed its own gates; this is the band where the page has visibly
         * drifted from the wall. Unmeasured, like the window.
         */
        const val HYBRID_DEGRADED_CONF = 0.5f

        /** Paint inliers a reloc lock needs to count as paint-backed. Unmeasured. */
        const val PAINT_BACKED_MIN_INLIERS = 8
    }
}
