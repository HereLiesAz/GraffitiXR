package com.hereliesaz.graffitixr.feature.ar

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Hands the latest value from a worker thread to the UI thread with at most one post outstanding.
 *
 * The standalone analyzer reports every tracked frame. Posting each one to the main thread lets a
 * slow UI thread accumulate a backlog it later replays as a burst of stale ticks. Here a frame only
 * replaces the pending value; a post is scheduled only when none is outstanding, and it applies
 * whatever is newest when it runs. Every value the UI sees is the latest at that moment, and the
 * queue holds at most one runnable.
 *
 * Lost-update free: the UI side clears [scheduled] *before* taking the value, so a value offered
 * after the take finds no post outstanding and schedules its own.
 */
internal class LatestValueUiPost<T : Any>(
    private val post: (() -> Unit) -> Unit,
    private val apply: (T) -> Unit,
) {
    private val latest = AtomicReference<T?>(null)
    private val scheduled = AtomicBoolean(false)

    /** Worker side: make [value] the pending update, posting a drain if none is outstanding. */
    fun offer(value: T) {
        latest.set(value)
        if (scheduled.compareAndSet(false, true)) post(::drain)
    }

    /**
     * Forget the pending value and any outstanding post. For a host that discards posted runnables
     * (a Handler purge on dispose): call it before offering again, or the dropped drain would leave
     * [scheduled] set and no later value would be posted.
     */
    fun reset() {
        latest.set(null)
        scheduled.set(false)
    }

    private fun drain() {
        scheduled.set(false)
        latest.getAndSet(null)?.let(apply)
    }
}
