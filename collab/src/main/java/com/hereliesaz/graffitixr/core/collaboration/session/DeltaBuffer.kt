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

    /** Append. Returns false when capped (caller should treat as a fatal reconnect failure). */
    fun append(seq: Long, op: Op, sizeBytes: Int): Boolean {
        if (sizeBytes > maxBytes) return false
        if (ring.size >= maxOps || totalBytes + sizeBytes > maxBytes) return false
        ring.addLast(Entry(seq, op, sizeBytes))
        totalBytes += sizeBytes
        return true
    }

    /** Discard all entries with seq <= upTo. */
    fun trimUpTo(upTo: Long) {
        while (ring.isNotEmpty() && ring.first().seq <= upTo) {
            val removed = ring.removeFirst()
            totalBytes -= removed.sizeBytes
        }
    }

    fun opsAfter(lastSeq: Long): List<Pair<Long, Op>> =
        ring.filter { it.seq > lastSeq }.map { it.seq to it.op }

    fun isEmpty(): Boolean = ring.isEmpty()
    fun size(): Int = ring.size
    fun bytes(): Long = totalBytes

    fun clear() {
        ring.clear()
        totalBytes = 0
    }
}
