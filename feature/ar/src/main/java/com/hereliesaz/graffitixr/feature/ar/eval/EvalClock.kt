package com.hereliesaz.graffitixr.feature.ar.eval

/**
 * `EVALUATION.md` §3.1, the **third** source of replay non-determinism — the one the fixed RNG seed
 * and the synchronous-reloc mode do not touch.
 *
 * §3.1 names three, and 6a.4 only closed two. The seed makes RANSAC draw the same samples; sync mode
 * makes the reloc thread see the same frames. Neither changes the fact that `DriftCostProbe` stamped
 * every row with `System.currentTimeMillis()`. Replay a recording twice and the two CSVs describe
 * the same frames at different times, so they cannot be aligned row-for-row — which is what a
 * parameter A/B needs before it can subtract one from the other. §3.1's wording is exact: *"Use the
 * frame timestamp from the recording, not the system clock, so runs align frame-for-frame."*
 *
 * ARCore's `Frame.getTimestamp()` is that timestamp. During `Session.startPlayback` it replays the
 * **recorded** values rather than the live clock, so two replays of one file produce identical
 * stamps — which is the whole property being bought.
 *
 * ## Why this is a separate object rather than three lines inside the probe
 *
 * `DriftCostProbe` needs a `Context` and writes files, so nothing in it is reachable from a JVM unit
 * test. The clock *decision* — which source to use, and what to do when the frame stamp is absent —
 * is the part that can be got wrong, so it lives here where a test can reach it.
 */
object EvalClock {

    /**
     * "No frame timestamp for this tick." Negative, not 0, and not for the usual reason: ARCore's
     * timestamps are monotonic-clock nanoseconds, so 0 is not a plausible reading — but a *default*
     * of 0 would silently stamp every row of a live (non-replay) session at the epoch, and the CSV
     * would look internally consistent while being wrong. A negative sentinel cannot be mistaken for
     * a reading by anything downstream.
     */
    const val NOT_SAMPLED_NS = -1L

    /**
     * The timestamp to write into a sample row.
     *
     * Prefers the frame clock; falls back to the wall clock when there is no frame stamp — a live
     * session that is not replaying anything, or a caller that has not been wired to pass one.
     * Falling back rather than refusing is deliberate: an ad-hoc debugging run with wall-clock rows
     * is still useful, whereas a probe that declined to log would be strictly worse than the
     * behaviour this replaces. Determinism is what §3.1 wants *for replays*, and a replay always has
     * a frame stamp.
     *
     * @param frameTimestampNs ARCore's `Frame.getTimestamp()`, or [NOT_SAMPLED_NS].
     */
    fun stampMs(frameTimestampNs: Long, wallClockMs: () -> Long): Long =
        if (frameTimestampNs > 0L) frameTimestampNs / 1_000_000L else wallClockMs()
}
