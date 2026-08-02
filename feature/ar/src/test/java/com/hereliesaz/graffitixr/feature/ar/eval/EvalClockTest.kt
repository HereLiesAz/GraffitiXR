package com.hereliesaz.graffitixr.feature.ar.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `EVALUATION.md` §3.1 item 3 — the clock a sample row is stamped with.
 *
 * The property that matters is not "the arithmetic is right"; it is that **two replays of one
 * recording produce identical stamps**. That is the whole reason the item exists: §3.1's other two
 * fixes make RANSAC and the reloc thread deterministic, and rows that still carry wall-clock times
 * cannot be aligned between runs, so a parameter A/B has nothing to subtract.
 *
 * So the headline test replays the same frame stamps through two "runs" whose wall clocks disagree
 * and asserts the outputs match — which is the claim, stated as a test, rather than a restatement of
 * the division.
 */
class EvalClockTest {

    /**
     * The property, directly. Two runs, identical recorded frame stamps, wall clocks an hour apart
     * and ticking at different rates. If the stamps did not come from the frames, these diverge on
     * the first row.
     */
    @Test
    fun `two replays of one recording produce identical stamps`() {
        val recordedFrames = longArrayOf(
            1_000_000_000L, 1_033_366_667L, 1_066_733_334L, 1_100_100_001L, 5_400_000_000L,
        )
        var wallA = 1_700_000_000_000L
        var wallB = 1_700_003_600_000L   // an hour later
        val runA = recordedFrames.map { EvalClock.stampMs(it) { wallA += 17; wallA } }
        val runB = recordedFrames.map { EvalClock.stampMs(it) { wallB += 31; wallB } }

        assertEquals("the same recording must stamp the same in both runs", runA, runB)
        // ...and the run really did have differing wall clocks, or the test proves nothing.
        assertNotEquals(wallA, wallB)
    }

    /**
     * Nanoseconds to milliseconds, truncating. Asserted against hand-computed values rather than by
     * dividing again, which would be the code restating itself.
     */
    @Test
    fun `a frame stamp is nanoseconds converted to milliseconds`() {
        assertEquals(1_000L, EvalClock.stampMs(1_000_000_000L) { fail() })
        assertEquals(1_033L, EvalClock.stampMs(1_033_366_667L) { fail() })
        // Truncation, not rounding: 1.9995 ms is 1, and that is fine — the stamp only has to be a
        // consistent function of the frame, not a rounded one.
        assertEquals(1L, EvalClock.stampMs(1_999_500L) { fail() })
    }

    /**
     * The fallback. A live session has no recorded frame to align against, so wall-clock rows are
     * the honest answer there — and refusing to log would be strictly worse than the behaviour this
     * replaces.
     */
    @Test
    fun `no frame stamp falls back to the wall clock`() {
        assertEquals(42L, EvalClock.stampMs(EvalClock.NOT_SAMPLED_NS) { 42L })
        assertEquals(42L, EvalClock.stampMs(-999L) { 42L })
    }

    /**
     * Zero must take the fallback, and this is the case a plainer `>= 0` test would get wrong.
     *
     * ARCore's timestamps are monotonic-clock nanoseconds, so 0 is not a reading. But a caller that
     * has not been wired up yet passes the Long default of 0, and treating that as a frame stamp
     * would silently file every row of a live session at the epoch — internally consistent, entirely
     * wrong, and invisible until someone tried to correlate the CSV with anything else.
     */
    @Test
    fun `a zero frame stamp is not a reading`() {
        assertEquals(7L, EvalClock.stampMs(0L) { 7L })
        assertTrue("the sentinel must be negative, so 0 cannot be mistaken for it",
            EvalClock.NOT_SAMPLED_NS < 0L)
    }

    /** Fails the test if the wall clock is consulted when it should not be. */
    private fun fail(): Long =
        throw AssertionError("the wall clock must not be read when a frame stamp is present")
}
