package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.graffitixr.common.model.KeyframeOrientations

/**
 * Accumulates per-keyframe device-orientation samples during standalone tracking and hands them out
 * as a [KeyframeOrientations] log for persistence — Phase 1b of the spherical-coverage map
 * (`docs/SPHERESLAM_SPHERE_MAP.md`). The analyzer records a sample at each atlas-growth keyframe (the
 * existing keyframe cadence); the view model snapshots the log onto the project.
 *
 * Bounded by [maxSamples] to honor the design's "lean" budget: once full, the oldest sample is
 * dropped so a long session can't grow this without limit. Thread-safe — [record] runs on the camera
 * analysis worker while [snapshot] runs on the view-model thread.
 *
 * Storage only: nothing here feeds relocalization yet, and the recorder is wired no-op by default
 * (the analyzer's callback defaults to discarding), so this changes no tracking behavior until the
 * sphere-map flag opts in.
 */
internal class KeyframeOrientationRecorder(
    private val maxSamples: Int = DEFAULT_MAX_SAMPLES,
) {
    private val lock = Any()
    private val timestamps = ArrayDeque<Long>()
    private val quats = ArrayDeque<FloatArray>()

    /**
     * Append one keyframe orientation. [quaternion] must be a normalized `[x, y, z, w]` (length 4);
     * a malformed sample is ignored rather than throwing, since this is a best-effort diagnostic
     * record on the hot path. The array is copied, so the caller may reuse its buffer.
     */
    fun record(timestampNs: Long, quaternion: FloatArray) {
        if (quaternion.size != 4) return
        synchronized(lock) {
            timestamps.addLast(timestampNs)
            quats.addLast(quaternion.copyOf())
            while (timestamps.size > maxSamples) {
                timestamps.removeFirst()
                quats.removeFirst()
            }
        }
    }

    /** Current number of recorded samples. */
    fun size(): Int = synchronized(lock) { timestamps.size }

    /** Snapshot the recorded samples as a persistable log, or null when none have been recorded. */
    fun snapshot(): KeyframeOrientations? = synchronized(lock) {
        if (timestamps.isEmpty()) return null
        val ts = LongArray(timestamps.size)
        val q = FloatArray(quats.size * 4)
        var i = 0
        val tsIt = timestamps.iterator()
        val qIt = quats.iterator()
        while (tsIt.hasNext()) {
            ts[i] = tsIt.next()
            qIt.next().copyInto(q, i * 4)
            i++
        }
        KeyframeOrientations(timestampsNs = ts, quaternions = q)
    }

    /** Drop all recorded samples (e.g. when the reference/session is reset). */
    fun clear() = synchronized(lock) {
        timestamps.clear()
        quats.clear()
    }

    companion object {
        /** Matches the feature-map's lean cap order of magnitude; one per atlas keyframe is far fewer. */
        const val DEFAULT_MAX_SAMPLES = 2048
    }
}
