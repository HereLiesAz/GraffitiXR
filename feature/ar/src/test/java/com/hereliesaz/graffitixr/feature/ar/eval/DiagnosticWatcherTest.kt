package com.hereliesaz.graffitixr.feature.ar.eval

import com.hereliesaz.graffitixr.common.model.CorrobGate
import com.hereliesaz.graffitixr.common.model.CorroborationDiagnostics
import com.hereliesaz.graffitixr.common.model.FusionDiagnostics
import com.hereliesaz.graffitixr.common.model.FusionState
import com.hereliesaz.graffitixr.common.model.GrowOutcome
import com.hereliesaz.graffitixr.common.model.RelocDiagnostics
import com.hereliesaz.graffitixr.common.model.RelocReject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rate limiter is the part of [DiagnosticWatcher] most likely to be silently wrong, and its
 * failure mode is indistinguishable from the failure it exists to detect: a limiter that refuses
 * everything produces a report reading "no watched transition occurred", which is what a session
 * with genuinely nothing wrong also produces.
 *
 * So these assert both directions — that it fires when it should and refuses when it should — and
 * that a refused edge is *dropped* rather than deferred, which is the behaviour that would otherwise
 * photograph the wrong frame and label it with the right event.
 */
class DiagnosticWatcherTest {

    private fun reloc(
        reject: RelocReject = RelocReject.NO_FINGERPRINT,
        gate: CorrobGate = CorrobGate.NOT_RUN,
        grow: GrowOutcome = GrowOutcome.NOT_RUN,
    ) = RelocDiagnostics(reject = reject, corrobGate = gate, growOutcome = grow)

    private fun fusion(
        state: FusionState = FusionState.NO_ANCHOR,
        correctionMm: Float = -1f,
    ) = FusionDiagnostics(state = state, correctionMm = correctionMm)

    private val corrob = CorroborationDiagnostics()

    private fun DiagnosticWatcher.tick(
        t: Long,
        reject: RelocReject = RelocReject.NO_FINGERPRINT,
        gate: CorrobGate = CorrobGate.NOT_RUN,
        grow: GrowOutcome = GrowOutcome.NOT_RUN,
        state: FusionState = FusionState.NO_ANCHOR,
        correctionMm: Float = -1f,
    ) = onTick(t, reloc(reject, gate, grow), corrob, fusion(state, correctionMm))

    /**
     * The very first tick must produce nothing, whatever it holds.
     *
     * Entering AR with a fingerprint already loaded arrives at `OK` on sample one. Treating that as
     * an acquisition edge would photograph the launch screen of every session and burn a slot of a
     * 16-capture budget on it.
     */
    @Test
    fun `the first tick is never a transition`() {
        val w = DiagnosticWatcher()
        assertNull(w.tick(0L, reject = RelocReject.OK, state = FusionState.COLD_SNAP))
    }

    @Test
    fun `losing the lock is photographed`() {
        val w = DiagnosticWatcher()
        w.tick(0L, reject = RelocReject.OK)
        assertEquals(
            CaptureTrigger.LOCK_LOST,
            w.tick(1_000L, reject = RelocReject.FEW_INLIERS),
        )
    }

    /**
     * Relocalization being switched off is not a lock being lost.
     *
     * The A/B harness disables it deliberately, and every run of the "fusion off" arm would open
     * with a capture of the moment the experiment started otherwise.
     */
    @Test
    fun `disabling relocalization is not a lost lock`() {
        val w = DiagnosticWatcher()
        w.tick(0L, reject = RelocReject.OK)
        assertNull(w.tick(1_000L, reject = RelocReject.DISABLED))
    }

    @Test
    fun `a once-only trigger fires once and never again`() {
        val w = DiagnosticWatcher()
        w.tick(0L, reject = RelocReject.NO_FEATURES)
        assertEquals(CaptureTrigger.LOCK_ACQUIRED, w.tick(1_000L, reject = RelocReject.OK))
        // Lose it and re-acquire, well past every cooldown.
        w.tick(100_000L, reject = RelocReject.NO_FEATURES)
        assertNull(w.tick(200_000L, reject = RelocReject.OK))
    }

    @Test
    fun `a repeatable trigger waits out its own cooldown`() {
        val w = DiagnosticWatcher()
        w.tick(0L, reject = RelocReject.OK)
        assertEquals(CaptureTrigger.LOCK_LOST, w.tick(1_000L, reject = RelocReject.FEW_MATCHES))
        // Re-acquire and lose again inside REPEAT_COOLDOWN_MS but outside GLOBAL_COOLDOWN_MS, so it
        // is this trigger's own limiter being tested and not the global one.
        w.tick(6_000L, reject = RelocReject.OK)
        assertNull(w.tick(9_000L, reject = RelocReject.FEW_MATCHES))
        // And past it.
        w.tick(30_000L, reject = RelocReject.OK)
        assertEquals(CaptureTrigger.LOCK_LOST, w.tick(40_000L, reject = RelocReject.FEW_MATCHES))
    }

    /**
     * Two edges a frame apart are one event, and the second image is the first image.
     *
     * `PROMOTED` here is a repeatable trigger with its own cooldown, so the tick is spaced past that
     * and the only thing left to refuse it is the global cooldown.
     */
    @Test
    fun `the global cooldown suppresses a different trigger too`() {
        val w = DiagnosticWatcher()
        w.tick(0L, reject = RelocReject.NO_FEATURES)
        assertEquals(CaptureTrigger.LOCK_ACQUIRED, w.tick(1_000L, reject = RelocReject.OK))
        assertNull(w.tick(2_000L, reject = RelocReject.OK, grow = GrowOutcome.PROMOTED))
    }

    /**
     * The global cooldown expires AT its length, not after it.
     *
     * Found by mutation testing: changing `<` to `<=` survived the whole suite, because every other
     * test sits either well inside the window or well outside it. The one tick that lands on the
     * boundary is masked — the per-trigger cooldown refuses it under both operators, so the global
     * cooldown's answer is invisible there.
     *
     * It matters in the direction that fails silently. An off-by-one toward refusing produces a
     * report saying no watched transition occurred, which is indistinguishable from a session where
     * genuinely nothing happened. So the boundary is pinned with a *different* trigger on the second
     * tick, which has no repeat history of its own and therefore nothing else that could refuse it.
     */
    @Test
    fun `a capture is permitted exactly one cooldown after the last`() {
        val w = DiagnosticWatcher()
        w.tick(0L, reject = RelocReject.NO_FEATURES)
        assertEquals(CaptureTrigger.LOCK_ACQUIRED, w.tick(1_000L, reject = RelocReject.OK))
        assertEquals(
            CaptureTrigger.PROMOTED,
            w.tick(
                1_000L + DiagnosticWatcher.GLOBAL_COOLDOWN_MS,
                reject = RelocReject.OK,
                grow = GrowOutcome.PROMOTED,
            ),
        )
    }

    /**
     * A correction parked exactly ON the threshold is a plateau, not a rise.
     *
     * Also found by mutation testing: `prevCorrectionMm < spikeMm` survived being loosened to `<=`,
     * because the spike tests all use values comfortably either side of 150mm and never land on it.
     *
     * The consequence of the loose form is not a missed capture but a repeating one — a correction
     * that settles precisely at `SPIKE_MM` would re-fire on every tick that clears the cooldowns,
     * and a budget of sixteen would be spent photographing one stationary state.
     */
    @Test
    fun `a correction sitting exactly on the threshold does not re-fire`() {
        val w = DiagnosticWatcher()
        w.tick(0L, correctionMm = 5f)
        // The rise onto the threshold is a real spike and must fire.
        assertEquals(
            CaptureTrigger.CORRECTION_SPIKE,
            w.tick(10_000L, correctionMm = DiagnosticWatcher.SPIKE_MM),
        )
        // Staying there, and going higher, is the same event. Spaced past every cooldown so only the
        // plateau test can be what refuses it.
        assertNull(w.tick(60_000L, correctionMm = DiagnosticWatcher.SPIKE_MM + 10f))
        assertNull(w.tick(120_000L, correctionMm = DiagnosticWatcher.SPIKE_MM))
    }

    /**
     * A suppressed edge is gone, not queued.
     *
     * This is the one that would be easy to get wrong by leaving the previous-state update inside
     * the fired branch: the edge would then be re-detected on every later tick and fire the instant
     * the cooldown lapsed, attaching a `LOCK_LOST` label to a frame from thirty seconds afterwards.
     */
    @Test
    fun `an edge suppressed by cooldown does not fire later`() {
        val w = DiagnosticWatcher()
        w.tick(0L, reject = RelocReject.NO_FEATURES)
        assertEquals(CaptureTrigger.LOCK_ACQUIRED, w.tick(1_000L, reject = RelocReject.OK))
        // Suppressed by the global cooldown.
        assertNull(w.tick(2_000L, reject = RelocReject.FEW_INLIERS))
        // Long past every cooldown, still holding the same (non-OK) state: nothing changed, so
        // nothing may fire.
        assertNull(w.tick(90_000L, reject = RelocReject.FEW_INLIERS))
    }

    @Test
    fun `a correction spike fires on the rise and not on the plateau`() {
        val w = DiagnosticWatcher()
        w.tick(0L, correctionMm = 5f)
        assertEquals(
            CaptureTrigger.CORRECTION_SPIKE,
            w.tick(10_000L, correctionMm = DiagnosticWatcher.SPIKE_MM + 1f),
        )
        // Still large, and still the same event. Spaced past both cooldowns so only the rise-versus-
        // level distinction can be what refuses it.
        assertNull(w.tick(60_000L, correctionMm = DiagnosticWatcher.SPIKE_MM + 50f))
    }

    /**
     * A correction of -1 means "no correction standing", not "a correction of minus one millimetre".
     *
     * If the sentinel were treated as a reading, every transition from "no correction" to a large
     * one would count as a rise from below the threshold — which is right by accident — but so would
     * a drop back to the sentinel and up again, giving a spike per relock. The rise must be measured
     * from a real previous reading.
     */
    @Test
    fun `the not-measured sentinel is not a previous reading`() {
        val w = DiagnosticWatcher()
        w.tick(0L, correctionMm = -1f)
        assertNull(w.tick(10_000L, correctionMm = DiagnosticWatcher.SPIKE_MM + 1f))
    }

    @Test
    fun `entering the 0_9 no-capture-pose state is photographed once`() {
        val w = DiagnosticWatcher()
        w.tick(0L, state = FusionState.WAITING_FOR_LOCK)
        assertEquals(
            CaptureTrigger.NO_CAPTURE_POSE,
            w.tick(1_000L, state = FusionState.NO_CAPTURE_POSE),
        )
    }

    @Test
    fun `the first gated corroboration is photographed`() {
        val w = DiagnosticWatcher()
        w.tick(0L, gate = CorrobGate.NO_PLACEMENT)
        assertEquals(
            CaptureTrigger.CORROBORATION_GATED,
            w.tick(1_000L, gate = CorrobGate.GATED),
        )
    }

    /**
     * When several edges land on one tick, the rarest wins.
     *
     * A promotion is the only irreversible act in the engine and happens a handful of times in a
     * session; a lock is acquired constantly. Returning the acquisition would spend the tick's one
     * capture on the event that will certainly come round again.
     */
    @Test
    fun `the rarest trigger wins a tie`() {
        val w = DiagnosticWatcher()
        w.tick(0L, reject = RelocReject.NO_FEATURES, state = FusionState.WAITING_FOR_LOCK)
        assertEquals(
            CaptureTrigger.PROMOTED,
            w.tick(
                1_000L,
                reject = RelocReject.OK,
                grow = GrowOutcome.PROMOTED,
                state = FusionState.COLD_SNAP,
            ),
        )
    }

    /** A losing candidate is not consolation-fired on the next tick either. */
    @Test
    fun `the loser of a tie is dropped, not deferred`() {
        val w = DiagnosticWatcher()
        w.tick(0L, reject = RelocReject.NO_FEATURES)
        w.tick(1_000L, reject = RelocReject.OK, grow = GrowOutcome.PROMOTED)
        assertNull(w.tick(120_000L, reject = RelocReject.OK))
    }

    /**
     * The budget counts every capture, not every capture of one kind.
     *
     * Flapping the lock ten times over ten minutes produces `LOCK_ACQUIRED` once and `LOCK_LOST`
     * repeatedly, spaced past every cooldown — so the only thing that can stop it is the ceiling,
     * and it must stop it across the two kinds together rather than per kind.
     */
    @Test
    fun `the capture budget is a hard ceiling`() {
        val w = DiagnosticWatcher(maxCaptures = 2)
        var t = 0L
        var fired = 0
        repeat(10) {
            t += 60_000L
            if (w.tick(t, reject = RelocReject.OK) != null) fired++
            t += 60_000L
            if (w.tick(t, reject = RelocReject.FEW_INLIERS) != null) fired++
        }
        assertEquals(2, fired)
        assertTrue(w.atCap)
        assertEquals(2, w.requestCount)
    }

    @Test
    fun `reset clears the latches and the budget`() {
        val w = DiagnosticWatcher()
        w.tick(0L, reject = RelocReject.NO_FEATURES)
        assertNotNull(w.tick(1_000L, reject = RelocReject.OK))
        w.reset()
        assertEquals(0, w.requestCount)
        // And the first tick after a reset is a first tick again.
        assertNull(w.tick(2_000L, reject = RelocReject.OK))
        w.tick(3_000L, reject = RelocReject.NO_FEATURES)
        assertEquals(CaptureTrigger.LOCK_ACQUIRED, w.tick(20_000L, reject = RelocReject.OK))
    }

    /**
     * Every trigger declares what to look for in its image.
     *
     * The report prints these beside the filenames, and a blank one turns an attachment into an
     * unlabelled photograph of a wall.
     */
    @Test
    fun `every trigger says what its picture is for`() {
        for (t in CaptureTrigger.entries) {
            assertTrue("${t.name} has no guidance", t.whatToLookFor.length > 10)
        }
    }
}
