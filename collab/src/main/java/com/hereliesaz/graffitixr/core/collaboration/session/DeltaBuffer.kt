// collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/session/DeltaBuffer.kt
package com.hereliesaz.graffitixr.core.collaboration.session

import com.hereliesaz.graffitixr.common.model.Op

/**
 * Bounded buffer of pending DELTAs, held so a guest that drops and reconnects can be replayed the
 * ops it missed instead of being re-sent the whole project.
 *
 * ## Why the cap is not simply "end the session"
 *
 * It used to be. [append] refused anything larger than the whole budget and refused once the budget
 * was full, and `HostSession.enqueueOp` treated a refusal as fatal — so a single ordinary edit could
 * kill a healthy session. Two ways, both routine:
 *
 *  * **One big op.** `Op.LayerBitmapReplace` carries a full-canvas PNG. One Liquify warp or one
 *    undo of a paint stroke on a large layer is megabytes, and a 5 MB ceiling made "the artist used
 *    the warp tool" a disconnect.
 *  * **No guest yet.** The buffer is only trimmed by a guest's DELTA_ACK. While the host is sitting
 *    in `WaitingForGuest` nothing acks, so an artist who starts sharing and keeps working fills the
 *    budget and tears down their own session before anyone has joined — and the replay history was
 *    worthless anyway, because a first-time guest is sent a bulk snapshot, not a replay.
 *
 * So the buffer now degrades instead of failing. Two mechanisms:
 *
 *  * [supersede] drops the entries a newer full-layer replacement makes redundant. An
 *    `Op.LayerBitmapReplace` defines that layer's pixels outright, so every earlier buffered op
 *    targeting the same layer is already baked into it. This bounds the common heavy case: repeated
 *    warps on one layer coalesce to the latest instead of accumulating.
 *  * When even that is not enough, [append] evicts the oldest entries and records that the history
 *    now has a hole ([hasGap]). A gapped buffer cannot answer a replay, and [opsAfter] says so by
 *    returning null — which the caller turns into a full bulk re-sync, the same path a first-time
 *    guest takes. Losing the shortcut is not losing the session.
 *
 * Not thread-safe by construction: `append` runs on the host's outbound path while `trimUpTo` runs
 * on its inbound loop, both on Dispatchers.IO, so every method is `@Synchronized`.
 */
internal class DeltaBuffer(
    /**
     * Total retained bytes before eviction starts. Sized for whole-canvas PNGs rather than for
     * small ops: at 5 MB a single [Op.LayerBitmapReplace] could exceed the entire budget on its own,
     * which is what made one warp fatal.
     */
    private val maxBytes: Long = 48L * 1024 * 1024,
    private val maxOps: Int = 2000,
) {
    private data class Entry(val seq: Long, val op: Op, val sizeBytes: Int)

    private val ring = ArrayDeque<Entry>()
    private var totalBytes: Long = 0

    /**
     * True once eviction has discarded an op a reconnecting guest might still need. Sticky until
     * [clear] or until a [trimUpTo] confirms the guest has acked past the hole.
     */
    private var gapped = false

    /** The highest seq evicted while gapped, so an ack past it can clear the gap. */
    private var gapThroughSeq = 0L

    /**
     * Append an op to the replay history. Never refuses: over budget it evicts oldest-first and
     * marks the history [hasGap].
     */
    @Synchronized
    fun append(seq: Long, op: Op, sizeBytes: Int) {
        if (sizeBytes < 0) return
        // A newer full-layer replacement subsumes everything buffered for that layer.
        supersede(op)
        ring.addLast(Entry(seq, op, sizeBytes))
        totalBytes += sizeBytes
        // Evict oldest-first until back within budget. The just-appended entry is never evicted —
        // dropping the op you were asked to retain would leave the newest state unrepresented.
        while (ring.size > 1 && (ring.size > maxOps || totalBytes > maxBytes)) {
            val removed = ring.removeFirst()
            totalBytes -= removed.sizeBytes
            gapped = true
            if (removed.seq > gapThroughSeq) gapThroughSeq = removed.seq
        }
    }

    /**
     * Drop buffered entries that [incoming] makes redundant.
     *
     * Only [Op.LayerBitmapReplace] supersedes, and only for its own layer: it replaces that layer's
     * pixels wholesale, so any earlier buffered op scoped to the same layer is already reflected in
     * it. Ops on other layers, and ops whose effect is not layer-scoped (reordering), are untouched.
     */
    private fun supersede(incoming: Op) {
        if (incoming !is Op.LayerBitmapReplace) return
        val layerId = incoming.layerId
        val it = ring.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.op.layerScope() == layerId) {
                totalBytes -= entry.sizeBytes
                it.remove()
            }
        }
    }

    /** The layer an op is scoped to, or null when it affects more than one (or none). */
    private fun Op.layerScope(): String? = when (this) {
        is Op.LayerBitmapReplace -> layerId
        is Op.LayerTransform -> layerId
        is Op.LayerPropsChange -> layerId
        is Op.StrokeComplete -> layerId
        is Op.TextContentChange -> layerId
        // LayerAdd introduces the layer the later ops depend on, and LayerRemove/LayerReorder
        // change the layer SET rather than one layer's contents. None may be dropped.
        is Op.LayerAdd, is Op.LayerRemove, is Op.LayerReorder -> null
    }

    /** Discard all entries with seq <= upTo. */
    @Synchronized
    fun trimUpTo(upTo: Long) {
        while (ring.isNotEmpty() && ring.first().seq <= upTo) {
            val removed = ring.removeFirst()
            totalBytes -= removed.sizeBytes
        }
        // The guest has acknowledged everything through the hole, so the hole no longer matters.
        if (gapped && upTo >= gapThroughSeq) {
            gapped = false
            gapThroughSeq = 0L
        }
    }

    /**
     * The ops a guest resuming at [lastSeq] still needs, or **null** when the history has a gap
     * covering that point — meaning replay cannot produce a correct canvas and the caller must send
     * a full bulk snapshot instead.
     */
    @Synchronized
    fun opsAfter(lastSeq: Long): List<Pair<Long, Op>>? {
        if (gapped && lastSeq < gapThroughSeq) return null
        return ring.filter { it.seq > lastSeq }.map { it.seq to it.op }
    }

    /** True when eviction has left a hole that makes [opsAfter] unable to answer a replay. */
    @Synchronized
    fun hasGap(): Boolean = gapped

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
        gapped = false
        gapThroughSeq = 0L
    }
}
