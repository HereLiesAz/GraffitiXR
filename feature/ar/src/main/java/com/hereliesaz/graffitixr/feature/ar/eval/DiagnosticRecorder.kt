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
    fun report(
        header: Map<String, String>,
        parameters: Map<String, String>,
        captures: List<String>,
        capturesTruncated: Boolean = false,
    ): String {
        // Snapshot under the lock, format outside it.
        //
        // This used to be @Synchronized as a whole, which meant a report — four histogram groupings
        // and twelve statistics passes, each boxing and sorting up to 1800 floats — held the same
        // monitor `record()` needs. `record()` runs on the AR tick, whose call site is careful never
        // to suspend; handing it a lock held across that much work put a frame drop behind a button
        // press for no reason. The copy is one array of references.
        val samples: List<Sample>
        val total: Long
        synchronized(this) {
            samples = ring.toList()
            total = totalSeen
        }
        return format(samples, total, header, parameters, captures, capturesTruncated)
    }

    private fun format(
        ring: List<Sample>,
        totalSeen: Long,
        header: Map<String, String>,
        parameters: Map<String, String>,
        captures: List<String>,
        capturesTruncated: Boolean,
    ): String {
        val sb = StringBuilder(4096)
        sb.appendLine("## GraffitiXR diagnostic report")
        sb.appendLine()
        for ((k, v) in header) sb.appendLine("- **$k**: $v")

        if (ring.isEmpty()) {
            sb.appendLine()
            sb.appendLine("**No samples recorded.** The diagnostics tick never ran — AR mode was not")
            sb.appendLine("entered, or tracking never started. That is itself the finding.")
            // The parameters and screenshots sections still print. An early return dropped both, so
            // the one path where "no screenshots" is guaranteed was also the one path that never
            // said so — and the parameters are what tell the reader which build produced the
            // nothing.
            appendParameters(sb, parameters)
            appendCaptures(sb, captures, capturesTruncated)
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
                // Distinguished from a fusion that was ON and never managed to correct anything.
                //
                // `ArRenderer.fusionEnabled` ships FALSE and its only writer sits behind the
                // debug-only eval panel, so in a release build every sample is DISABLED and the bare
                // "never corrected" line would print on 100% of field reports — a finding that is a
                // constant is not a finding, and the first reader to chase it wastes the trip.
                if (ring.all { it.fusion.state == FusionState.DISABLED }) {
                    add("fusion was OFF for the whole run (expected — it ships default-off)")
                } else {
                    add("fusion never corrected the overlay")
                }
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
        // `inlierRatio` has no sentinel of its own — its getter returns a bare 0 both when nothing
        // was attempted (no matches to divide by) and when everything was attempted and RANSAC
        // rejected all of it. Those are opposite findings, and the second is the more diagnostic of
        // the two: forty correspondences a frame with zero inliers is the textbook "aimed at the
        // wrong wall".
        //
        // A previous version filtered on `it > 0f` and a comment claimed 0 could only mean the
        // first. It meant both, so the run that most needed this row reported `never measured`.
        // Mapped to the project's -1 sentinel by the condition that actually separates them.
        stat(
            sb, "inlierRatio",
            ring.map { if (it.reloc.matches > 0) it.reloc.inlierRatio else NOT_MEASURED },
        ) { it >= 0f }
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

        appendParameters(sb, parameters)
        appendCaptures(sb, captures, capturesTruncated)
        return sb.toString()
    }

    /** Every one is an unmeasured prior, and the reader is usually not the person holding the phone. */
    private fun appendParameters(sb: StringBuilder, parameters: Map<String, String>) {
        sb.appendLine("### Parameters in force")
        sb.appendLine()
        for ((k, v) in parameters.toSortedMap()) sb.appendLine("- `$k` = $v")
        sb.appendLine()
    }

    private fun appendCaptures(
        sb: StringBuilder,
        captures: List<String>,
        capturesTruncated: Boolean,
    ) {
        sb.appendLine("### Screenshots")
        sb.appendLine()
        if (captures.isEmpty()) {
            sb.appendLine("None captured — no watched transition occurred during this run.")
        } else {
            captures.forEach { sb.appendLine("- $it") }
        }
        // A list that stops at the ceiling reads as "and then nothing else happened". It has to say
        // which of the two it is, or it reproduces the exact silent-absence confusion this report
        // was written to end, one section down from where the report says so.
        if (capturesTruncated) {
            sb.appendLine()
            sb.appendLine(
                "**Capture budget exhausted** — later watched transitions occurred and were NOT " +
                    "photographed.",
            )
        }
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
        /**
         * Samples retained. Bounded so a long session cannot grow it.
         *
         * Deliberately expressed in samples and not in minutes: the diagnostics tick is
         * `frameCount % 4` in `ArRenderer`, a quarter of the *achieved* GL frame rate, so the wall
         * time this covers floats with load and display — roughly two minutes at 30 fps, half that
         * on a 90 Hz panel, twice it when SLAM is struggling. The report prints the measured span of
         * its actual window rather than restating this as a duration.
         */
        const val DEFAULT_CAPACITY = 1800

        /** The project-wide "not measured" marker. Negative, never 0. */
        private const val NOT_MEASURED = -1f
    }
}
