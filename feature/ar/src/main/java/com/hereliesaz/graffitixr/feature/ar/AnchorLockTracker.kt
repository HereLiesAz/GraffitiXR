package com.hereliesaz.graffitixr.feature.ar

import kotlin.math.sqrt

/**
 * Pragmatic "has the overlay anchored to one spot?" gate for the first-run doodle demo.
 *
 * Fed the anchor's world-space translation once per frame, it keeps a sliding window of the last
 * [windowSize] positions and reports [locked] once the window is full AND its spatial jitter — the
 * farthest any sample sits from the window centroid — has stayed under [jitterThresholdMeters]. A
 * full 30-sample window at ~30 fps means the anchor held one location for ~1 second, which is the
 * "consistently anchored" signal we swap the scribble for the user's artwork on.
 *
 * [locked] latches: once true it stays true until [reset], so a single post-lock jitter frame can't
 * un-fire the swap mid-transition. Pure/deterministic and Android-free for unit testing; the choice
 * of a pragmatic jitter gate (vs. bootstrapping a fingerprint and gating on PnP pose stability) is
 * deliberate — see PR notes.
 */
class AnchorLockTracker(
    private val windowSize: Int = 30,
    private val jitterThresholdMeters: Float = 0.01f,
) {
    init {
        require(windowSize >= 2) { "windowSize must be >= 2, was $windowSize" }
        require(jitterThresholdMeters > 0f) { "jitterThresholdMeters must be > 0" }
    }

    private val xs = FloatArray(windowSize)
    private val ys = FloatArray(windowSize)
    private val zs = FloatArray(windowSize)
    private var count = 0
    private var head = 0

    /** True once the anchor has held one spot for a full window; latches until [reset]. */
    var locked = false
        private set

    /** Farthest sample from the window centroid, in meters. `Float.MAX_VALUE` until the window fills. */
    var jitterMeters = Float.MAX_VALUE
        private set

    /** 0..1 "how still is it" for UI (1 = dead still). 0 until the window fills. */
    var stability = 0f
        private set

    fun reset() {
        count = 0
        head = 0
        locked = false
        jitterMeters = Float.MAX_VALUE
        stability = 0f
    }

    /** Feed one anchor world position; returns the current [locked] state. */
    fun update(x: Float, y: Float, z: Float): Boolean {
        xs[head] = x
        ys[head] = y
        zs[head] = z
        head = (head + 1) % windowSize
        if (count < windowSize) count++
        recompute()
        return locked
    }

    private fun recompute() {
        if (count < windowSize) {
            jitterMeters = Float.MAX_VALUE
            stability = 0f
            return
        }
        var cx = 0f
        var cy = 0f
        var cz = 0f
        for (i in 0 until windowSize) {
            cx += xs[i]; cy += ys[i]; cz += zs[i]
        }
        cx /= windowSize; cy /= windowSize; cz /= windowSize

        var maxD = 0f
        for (i in 0 until windowSize) {
            val dx = xs[i] - cx
            val dy = ys[i] - cy
            val dz = zs[i] - cz
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d > maxD) maxD = d
        }
        jitterMeters = maxD
        stability = (1f - maxD / jitterThresholdMeters).coerceIn(0f, 1f)
        if (maxD < jitterThresholdMeters) locked = true
    }
}
