// collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/session/DeltaBuffer.kt
package com.hereliesaz.graffitixr.core.collaboration.session

import com.hereliesaz.graffitixr.common.model.Op

/**
 * Bounded buffer of pending DELTAs during the host's reconnect window. When the
 * window expires or the cap is exceeded, the buffer is dropped and the session
 * ends; that is the host's signal that the guest cannot resume.
 */
internal class DeltaBuffer(
    private val maxBytes: Long = 5L * 1024 * 1024,
    private val maxOps: Int = 1000,
) {
    private data class Entry(val seq: Long, val op: Op, val sizeBytes: Int)

    private val ring = ArrayDeque<Entry>()
    private var totalBytes: Long = 0

    // append() runs on the host's outbound loop while trimUpTo() runs on its inbound loop;
    // both are independent coroutines on Dispatchers.IO, so all access to ring/totalBytes
    // is synchronized to avoid lost updates and ConcurrentModificationException.
    /** Append. Returns false when capped (caller should treat as a fatal reconnect failure). */
    @Synchronized
    fun append(seq: Long, op: Op, sizeBytes: Int): Boolean {
        if (sizeBytes < 0 || sizeBytes > maxBytes) return false
        if (ring.size >= maxOps || totalBytes + sizeBytes > maxBytes) return false
        ring.addLast(Entry(seq, op, sizeBytes))
        totalBytes += sizeBytes
        return true
    }

    /** Discard all entries with seq <= upTo. */
    @Synchronized
    fun trimUpTo(upTo: Long) {
        while (ring.isNotEmpty() && ring.first().seq <= upTo) {
            val removed = ring.removeFirst()
            totalBytes -= removed.sizeBytes
        }
    }

    @Synchronized
    fun opsAfter(lastSeq: Long): List<Pair<Long, Op>> =
        ring.filter { it.seq > lastSeq }.map { it.seq to it.op }

    @Synchronized
    fun isEmpty(): Boolean = ring.isEmpty()
    @Synchronized
    fun size(): Int = ring.size
    @Synchronized
    fun bytes(): Long = totalBytes

    @Synchronized
    fun clear() {
        ring.clear()
        totalBytes = 0
    }
}
