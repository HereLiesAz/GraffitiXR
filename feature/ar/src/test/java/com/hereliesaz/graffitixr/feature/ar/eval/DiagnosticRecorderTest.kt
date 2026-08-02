package com.hereliesaz.graffitixr.feature.ar.eval

import com.hereliesaz.graffitixr.common.model.CorrobGate
import com.hereliesaz.graffitixr.common.model.CorroborationDiagnostics
import com.hereliesaz.graffitixr.common.model.FusionDiagnostics
import com.hereliesaz.graffitixr.common.model.FusionState
import com.hereliesaz.graffitixr.common.model.GrowOutcome
import com.hereliesaz.graffitixr.common.model.RelocDiagnostics
import com.hereliesaz.graffitixr.common.model.RelocReject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The report's job is to answer "did this ever happen" for someone who was not holding the phone.
 * These pin the two ways it could fail at that quietly: reporting nothing as if it were something
 * (the `-1` sentinels averaged into the statistics), and reporting something as if it were nothing
 * (an empty window rendered as a healthy-looking report of zeros).
 */
class DiagnosticRecorderTest {

    private var clock = 0L
    private fun recorder(capacity: Int = 100) =
        DiagnosticRecorder(capacity = capacity, nowMs = { clock })

    private fun DiagnosticRecorder.tick(
        reject: RelocReject = RelocReject.OK,
        gate: CorrobGate = CorrobGate.GATED,
        grow: GrowOutcome = GrowOutcome.NOT_RUN,
        state: FusionState = FusionState.HOLDING,
        radiusPx: Float = 12f,
        correctionMm: Float = 4f,
        matches: Int = 100,
        inliers: Int = 80,
    ) {
        clock += 66L
        record(
            RelocDiagnostics(
                reject = reject, matches = matches, inliers = inliers,
                corrobGate = gate, growOutcome = grow,
            ),
            CorroborationDiagnostics(searchRadiusPx = radiusPx),
            FusionDiagnostics(state = state, correctionMm = correctionMm),
            wallPoints = 500,
            progress = 0.25f,
        )
    }

    /**
     * A report with no samples must say so in words.
     *
     * This is the single most likely real outcome — the tick never ran because AR was never entered
     * — and rendering it as a table of zeros and empty histograms would read as "everything measured
     * zero", which is a completely different and much more alarming finding.
     */
    @Test
    fun `an empty window states that it is empty`() {
        val r = recorder()
        assertTrue(r.isEmpty())
        val out = r.report(mapOf("build" to "debug"), emptyMap(), emptyList())
        assertTrue(out, out.contains("No samples recorded"))
        // And it must not print a numbers table that has nothing behind it.
        assertFalse(out, out.contains("### What it measured"))
    }

    /** The ring is bounded: a long session cannot grow it, and the window it reports is the tail. */
    @Test
    fun `the ring is bounded and keeps the newest samples`() {
        val r = recorder(capacity = 10)
        repeat(5) { r.tick(reject = RelocReject.NO_FEATURES) }
        repeat(50) { r.tick(reject = RelocReject.OK) }
        val out = r.report(emptyMap(), emptyMap(), emptyList())
        assertTrue(out, out.contains("10 samples"))
        // The 5 early NO_FEATURES ticks have been evicted, so OK must be the whole window.
        assertTrue(out, out.contains("OK ×10 (100%)"))
        // But the total is still reported, so the reader knows the window is a tail and not the run.
        assertTrue(out, out.contains("of 55 total"))
    }

    /**
     * The four "never happened" answers, stated rather than left to be derived.
     *
     * A reader can in principle work these out from the histograms above them. In practice the
     * absence of a value from a list is the single easiest thing to miss when skimming, and these
     * four absences are the findings.
     */
    @Test
    fun `it names the stages that never ran`() {
        val r = recorder()
        repeat(20) { r.tick(reject = RelocReject.FEW_INLIERS, gate = CorrobGate.NO_PLACEMENT, state = FusionState.NO_ANCHOR) }
        val out = r.report(emptyMap(), emptyMap(), emptyList())
        assertTrue(out, out.contains("relocalization never locked"))
        assertTrue(out, out.contains("fusion never corrected the overlay"))
        assertTrue(out, out.contains("gated corroboration match never ran"))
        assertTrue(out, out.contains("self-grow never promoted"))
    }

    @Test
    fun `a healthy run reports nothing as never having happened`() {
        val r = recorder()
        r.tick(reject = RelocReject.OK, gate = CorrobGate.GATED, grow = GrowOutcome.PROMOTED, state = FusionState.COLD_SNAP)
        val out = r.report(emptyMap(), emptyMap(), emptyList())
        assertTrue(out, out.contains("every stage ran at least once"))
    }

    /**
     * `-1` means not measured, and averaging it in would make an unmeasured channel look like a
     * badly performing one.
     *
     * The row must say `never measured` and carry a sample count of zero — not a min of -1, which a
     * reader would try to interpret as a radius.
     */
    @Test
    fun `an all-sentinel channel reports never measured rather than minus one`() {
        val r = recorder()
        repeat(10) { r.tick(radiusPx = -1f) }
        val out = r.report(emptyMap(), emptyMap(), emptyList())
        val row = out.lines().first { it.startsWith("| searchRadiusPx") }
        assertTrue(row, row.contains("never measured"))
        assertFalse(row, row.contains("-1"))
    }

    /** A partly-measured channel reports only the real readings, and says how many there were. */
    @Test
    fun `sentinels are excluded from the statistics of a partly measured channel`() {
        val r = recorder()
        repeat(7) { r.tick(radiusPx = -1f) }
        r.tick(radiusPx = 10f)
        r.tick(radiusPx = 20f)
        r.tick(radiusPx = 30f)
        val out = r.report(emptyMap(), emptyMap(), emptyList())
        val row = out.lines().first { it.startsWith("| searchRadiusPx") }
        // min 10, median 20, max 30, over 3 samples — and NOT over 10.
        assertEquals("| searchRadiusPx | 10 | 20 | 30 | 3 |", row.trim())
    }

    /**
     * The Screenshots section must distinguish "none taken" from "some taken and not attached".
     *
     * A reader who sees no images and no section assumes the feature is not there. One who sees the
     * sentence knows the absence is itself a reading.
     */
    @Test
    fun `the screenshots section says so when there are none`() {
        val r = recorder()
        r.tick()
        val none = r.report(emptyMap(), emptyMap(), emptyList())
        assertTrue(none, none.contains("None captured"))
        val some = r.report(emptyMap(), emptyMap(), listOf("01_LOCK_LOST.png — pointed at a blank wall"))
        assertTrue(some, some.contains("01_LOCK_LOST.png"))
        assertFalse(some, some.contains("None captured"))
    }

    /** Parameters are printed sorted, so two reports from different runs diff cleanly. */
    @Test
    fun `parameters are printed in a stable order`() {
        val r = recorder()
        r.tick()
        val a = r.report(emptyMap(), linkedMapOf("z" to "1", "a" to "2"), emptyList())
        val b = r.report(emptyMap(), linkedMapOf("a" to "2", "z" to "1"), emptyList())
        assertEquals(
            a.substringAfter("### Parameters in force"),
            b.substringAfter("### Parameters in force"),
        )
        assertTrue(a, a.indexOf("`a` = 2") < a.indexOf("`z` = 1"))
    }

    @Test
    fun `clear empties the window and the total`() {
        val r = recorder()
        repeat(5) { r.tick() }
        r.clear()
        assertTrue(r.isEmpty())
        assertTrue(r.report(emptyMap(), emptyMap(), emptyList()).contains("No samples recorded"))
    }

    /** Whole numbers print as integers; the rest to three places. A `12.000 px` radius reads badly. */
    @Test
    fun `whole numbers do not print a decimal tail`() {
        val r = recorder()
        r.tick(radiusPx = 12f)
        r.tick(radiusPx = 12.5f)
        val out = r.report(emptyMap(), emptyMap(), emptyList())
        val row = out.lines().first { it.startsWith("| searchRadiusPx") }
        assertEquals("| searchRadiusPx | 12 | 12.500 | 12.500 | 2 |", row.trim())
    }
}
