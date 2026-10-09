package com.hereliesaz.graffitixr.feature.ar.eval

import java.util.Locale

/**
 * Reading rules for the five-slot array `SlamManager.getStageTimings()` returns.
 *
 * Slots 0-3 (`voxelUpdate`, `voxelKeyframe`, `surfaceMesh`, `draw`) name stages of a splat-rendering
 * pipeline the native engine never implemented; `MobileGS::getStageTimingsAndReset` reports them as
 * [NOT_MEASURED] unconditionally. Slot [PNP_RELOC] is the only timed stage, and it too reads
 * [NOT_MEASURED] for any poll interval in which no relocalization pass ran.
 *
 * The CSV in [EvalSampleLog] keeps all five columns (its header is a stable contract for existing
 * analysis), writing the sentinel through verbatim. Anything that *shows* or *aggregates* these
 * values must go through [isMeasured] instead: a `-1.0` averaged into a cost, or printed as a
 * duration, is a fabricated number.
 */
object StageTimings {
    /** Index of the one stage the native engine actually times. */
    const val PNP_RELOC: Int = 4

    /** Native sentinel for "this stage was not timed in this interval". */
    const val NOT_MEASURED: Float = -1f

    /** True when [ms] is a real duration rather than the [NOT_MEASURED] sentinel (or NaN). */
    fun isMeasured(ms: Float): Boolean = ms >= 0f

    /**
     * The HUD readout: the pnpReloc duration to one decimal, or an em dash when the slot is absent
     * or unmeasured. Slots 0-3 are deliberately omitted — they can never carry a value, so printing
     * them only displays four `-1.0`s that read like timings.
     */
    fun formatHud(stageMs: FloatArray): String {
        val v = stageMs.getOrNull(PNP_RELOC) ?: return "—"
        return if (isMeasured(v)) String.format(Locale.US, "%.1f", v) else "—"
    }
}
