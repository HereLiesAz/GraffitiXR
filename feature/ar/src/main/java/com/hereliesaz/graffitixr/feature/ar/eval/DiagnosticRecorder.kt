package com.hereliesaz.graffitixr.feature.ar.eval

import com.hereliesaz.graffitixr.common.model.CorrobGate
import com.hereliesaz.graffitixr.common.model.CorroborationDiagnostics
import com.hereliesaz.graffitixr.common.model.FusionDiagnostics
import com.hereliesaz.graffitixr.common.model.FusionState
import com.hereliesaz.graffitixr.common.model.GrowOutcome
import com.hereliesaz.graffitixr.common.model.RelocDiagnostics
import com.hereliesaz.graffitixr.common.model.RelocReject

/**
 * Rolling capture of the diagnostic channels, and the report that gets pasted back to whoever is
 * debugging.
 *
 * ## Why aggregates and not a snapshot
 *
 * A screenshot of the HUD answers "what is happening now". The question that actually matters for
 * this system is **"did this ever happen"** — because every expensive failure here has been a stage
 * that silently did not run, and a stage that never runs looks identical at every single instant.
 * `Corrob: design not placed` in one screenshot cannot distinguish "never gated once" from "gated
 * fine a second ago and the artist has just looked away".
 *
 * So this keeps a bounded window of samples and reports **distributions**: how many ticks each
 * reason code held, and min/median/max for each number. One paste then answers questions no
 * sequence of screenshots could — whether corroboration ever gated at all, whether the promotion
 * gate ever passed, whether the correction magnitude is steady or spiking.
 *
 * ## Why it also reports the parameters
 *
 * Every constant in `PARAMETERS.md` is a pre-registered prior that no experiment has moved. A report
 * of behaviour without the values that produced it cannot be acted on — and the person reading it is
 * usually not the person holding the phone.
 */
class DiagnosticRecorder(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** One tick's worth. Flat and primitive so the ring stays cheap at 15 Hz. */
    private class Sample(
        val tsMs: Long,
        val reloc: RelocDiagnostics,
        val corrob: CorroborationDiagnostics,
        val fusion: FusionDiagnostics,
        val wallPoints: Int,
        val progress: Float,
    )

    private val ring = ArrayDeque<Sample>(capacity)
    private var totalSeen = 0L

    @Synchronized
    fun record(
        reloc: RelocDiagnostics,
        corrob: CorroborationDiagnostics,
        fusion: FusionDiagnostics,
        wallPoints: Int,
        progress: Float,
    ) {
        ring.addLast(Sample(nowMs(), reloc, corrob, fusion, wallPoints, progress))
        while (ring.size > capacity) ring.removeFirst()
        totalSeen++
    }

    @Synchronized
    fun clear() {
        ring.clear()
        totalSeen = 0
    }

    @Synchronized
    fun isEmpty(): Boolean = ring.isEmpty()

    /**
     * The pasteable report.
     *
     * Markdown, because it is going into a chat window. Ordered by what a reader checks first: what
     * ran, then what it measured, then the settings that produced it.
     *
     * @param header build/device lines the recorder cannot know.
     * @param parameters `PARAMETERS.md` name → value, in force for this run.
     * @param captures filenames of any auto-captured screenshots, so the reader knows what to expect
     *   attached and — more importantly — knows some exist when none are attached.
     */
    @Synchronized
    fun report(
        header: Map<String, String>,
        parameters: Map<String, String>,
        captures: List<String>,
    ): String {
        val sb = StringBuilder(4096)
        sb.appendLine("## GraffitiXR diagnostic report")
        sb.appendLine()
        for ((k, v) in header) sb.appendLine("- **$k**: $v")

        if (ring.isEmpty()) {
            sb.appendLine()
            sb.appendLine("**No samples recorded.** The diagnostics tick never ran — AR mode was not")
            sb.appendLine("entered, or tracking never started. That is itself the finding.")
            return sb.toString()
        }

        val spanMs = ring.last().tsMs - ring.first().tsMs
        sb.appendLine("- **window**: ${ring.size} samples over ${spanMs / 1000}s (of $totalSeen total)")
        sb.appendLine()

        // --- What ran, and what did not. The reason codes come first because they are the ones
        // that answer "did this ever happen", which no instantaneous view can.
        sb.appendLine("### What ran")
        sb.appendLine()
        sb.appendLine(histogram("Fusion", ring.map { it.fusion.state.name }))
        sb.appendLine(histogram("Reloc", ring.map { it.reloc.reject.name }))
        sb.appendLine(histogram("CorrobGate", ring.map { it.reloc.corrobGate.name }))
        sb.appendLine(histogram("SelfGrow", ring.map { it.reloc.growOutcome.name }))
        // Blank line before the next block or markdown folds it into the list above, and the
        // findings end up rendered as a continuation of the SelfGrow histogram.
        sb.appendLine()

        // The four questions this report exists to answer, stated as answers rather than left for
        // the reader to derive from the histograms above.
        sb.appendLine("**Never happened:**")
        val never = buildList {
            if (ring.none { it.reloc.reject == RelocReject.OK }) add("relocalization never locked")
            if (ring.none { it.fusion.state == FusionState.COLD_SNAP ||
                    it.fusion.state == FusionState.BLENDING ||
                    it.fusion.state == FusionState.HOLDING }) {
                add("fusion never corrected the overlay")
            }
            if (ring.none { it.reloc.corrobGate == CorrobGate.GATED }) {
                add("the gated corroboration match never ran (Phase 4 inactive for this run)")
            }
            if (ring.none { it.reloc.growOutcome == GrowOutcome.PROMOTED }) {
                add("self-grow never promoted (expected — it ships default-off)")
            }
        }
        if (never.isEmpty()) sb.appendLine("- (nothing — every stage ran at least once)")
        else never.forEach { sb.appendLine("- $it") }
        sb.appendLine()

        // --- Numbers. min/median/max rather than a mean: these distributions are not symmetric and
        // the tails are the interesting part. A search radius pinned at its floor and one pinned at
        // its ceiling have the same mean if they alternate.
        sb.appendLine("### What it measured")
        sb.appendLine()
        sb.appendLine("| channel | min | median | max | samples |")
        sb.appendLine("|---|---|---|---|---|")
        // The one filter here that is not the project's `>= 0` sentinel rule. `inlierRatio` is a
        // computed getter that returns exactly 0 when there were no matches to divide by, so a 0 in
        // this channel means "nothing was attempted", not "nothing survived RANSAC". Admitting it
        // would drag the median of a run that mostly had no fingerprint down toward zero and make it
        // look like a run that relocalized badly.
        stat(sb, "inlierRatio", ring.map { it.reloc.inlierRatio }) { it > 0f }
        stat(sb, "backboneFeatures", ring.map { it.reloc.backboneFeatures.toFloat() }) { it >= 0f }
        stat(sb, "corrobPredicted", ring.map { it.reloc.corrobPredicted.toFloat() }) { it >= 0f }
        stat(sb, "corrobMatched", ring.map { it.reloc.corrobMatched.toFloat() }) { it >= 0f }
        stat(sb, "corrobLoneSkips", ring.map { it.reloc.corrobLoneSkips.toFloat() }) { it >= 0f }
        stat(sb, "searchRadiusPx", ring.map { it.corrob.searchRadiusPx }) { it >= 0f }
        stat(sb, "relocReprojPx", ring.map { it.corrob.relocReprojPx }) { it >= 0f }
        stat(sb, "inlierSpread", ring.map { it.corrob.inlierSpread }) { it >= 0f }
        stat(sb, "correctionMm", ring.map { it.fusion.correctionMm }) { it >= 0f }
        stat(sb, "correctionDeg", ring.map { it.fusion.correctionDeg }) { it >= 0f }
        stat(sb, "wallPoints", ring.map { it.wallPoints.toFloat() }) { it >= 0f }
        stat(sb, "paintingProgress", ring.map { it.progress }) { it >= 0f }
        sb.appendLine()

        val last = ring.last()
        sb.appendLine("- snaps accepted/refused: ${last.fusion.snapsAccepted} / ${last.fusion.snapsRejected}")
        sb.appendLine()

        // --- Parameters. Every one is an unmeasured prior, and the reader is usually not the person
        // holding the phone.
        sb.appendLine("### Parameters in force")
        sb.appendLine()
        for ((k, v) in parameters.toSortedMap()) sb.appendLine("- `$k` = $v")
        sb.appendLine()

        sb.appendLine("### Screenshots")
        sb.appendLine()
        if (captures.isEmpty()) {
            sb.appendLine("None captured — no watched transition occurred during this run.")
        } else {
            captures.forEach { sb.appendLine("- $it") }
        }
        return sb.toString()
    }

    /** `name: A x12 (40%), B x18 (60%)`, most frequent first. */
    private fun histogram(label: String, values: List<String>): String {
        if (values.isEmpty()) return "- **$label**: (none)"
        val counts = values.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        val total = values.size
        return counts.joinToString(
            prefix = "- **$label**: ", separator = ", ",
        ) { "${it.key} ×${it.value} (${it.value * 100 / total}%)" }
    }

    /**
     * One row of the numbers table, over the samples where [measured] holds.
     *
     * The filter is the point: every channel here uses a negative sentinel for "not measured", and
     * averaging those in would drag every statistic toward -1 and make an unmeasured run look like a
     * badly performing one. The sample count is printed so a row backed by three readings is not
     * mistaken for one backed by three hundred.
     */
    private fun stat(sb: StringBuilder, label: String, all: List<Float>, measured: (Float) -> Boolean) {
        val vs = all.filter { it.isFinite() && measured(it) }.sorted()
        if (vs.isEmpty()) {
            sb.appendLine("| $label | — | — | — | 0 (never measured) |")
            return
        }
        val median = vs[vs.size / 2]
        sb.appendLine(
            "| $label | ${fmt(vs.first())} | ${fmt(median)} | ${fmt(vs.last())} | ${vs.size} |",
        )
    }

    private fun fmt(v: Float): String =
        if (v == v.toLong().toFloat()) v.toLong().toString()
        else String.format(java.util.Locale.US, "%.3f", v)

    companion object {
        /** ~2 minutes at the ~15 Hz diagnostics tick. Bounded so a long session cannot grow it. */
        const val DEFAULT_CAPACITY = 1800
    }
}
