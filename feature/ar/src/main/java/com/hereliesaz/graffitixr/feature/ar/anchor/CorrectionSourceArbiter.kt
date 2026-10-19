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

    /** Forget the hybrid history — a new anchor (re-capture) starts from "reloc until hybrid accepts". */
    fun reset() {
        lastHybridAcceptedNs = NEVER
    }

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
     */
    fun select(
        nowNs: Long,
        hybridAccepted: Boolean,
        hybridFrameReady: Boolean,
        hasRelocCapturePose: Boolean,
        relocGated: Boolean = false,
    ): Source {
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
    }
}
