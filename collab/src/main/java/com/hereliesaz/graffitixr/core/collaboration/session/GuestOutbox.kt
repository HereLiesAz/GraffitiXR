package com.hereliesaz.graffitixr.core.collaboration.session

import com.hereliesaz.graffitixr.common.model.Op

/**
 * The guest's edits not yet acknowledged by the host (protocol v4).
 *
 * Every op gets the next guest sequence number and stays queued until a GUEST_OP_ACK covers it,
 * so an edit made during a reconnect window — or in flight when the socket dropped — is resent on
 * the next connection instead of lost. The host drops any guestSeq it already handled, so a resend
 * is never applied twice.
 *
 * Bounded: newer absolute-state ops supersede older queued ones by the same rules the host's
 * replay buffer uses ([subsumedBy]), and past [maxOps] the oldest is evicted. Eviction loses that
 * edit for the host — acceptable only because it is the oldest of an implausibly long backlog
 * (the queue clears at every ack, about once per round trip while connected); [evicted] counts it.
 */
internal class GuestOutbox(private val maxOps: Int = DEFAULT_MAX_OPS) {

    private class Entry(val guestSeq: Long, val op: Op)

    private val queue = ArrayDeque<Entry>()
    private var nextSeq = 1L

    var evicted: Int = 0
        private set

    /** Queue [op] and return its guest sequence number. */
    @Synchronized
    fun add(op: Op): Long {
        subsumedBy(op)?.let { subsumes -> queue.removeAll { subsumes(it.op) } }
        val seq = nextSeq++
        queue.addLast(Entry(seq, op))
        while (queue.size > maxOps) {
            queue.removeFirst()
            evicted++
        }
        return seq
    }

    /** Unacknowledged ops with guestSeq > [afterSeq], oldest first. */
    @Synchronized
    fun pendingAfter(afterSeq: Long): List<Pair<Long, Op>> =
        queue.filter { it.guestSeq > afterSeq }.map { it.guestSeq to it.op }

    /** The host has handled everything up to and including [seq]. */
    @Synchronized
    fun ackUpTo(seq: Long) {
        while (queue.isNotEmpty() && queue.first().guestSeq <= seq) queue.removeFirst()
    }

    @Synchronized
    fun size(): Int = queue.size

    /** Drop everything (a new host session: its bulk snapshot is the new baseline). */
    @Synchronized
    fun clear() = queue.clear()

    companion object {
        const val DEFAULT_MAX_OPS = 256
    }
}
