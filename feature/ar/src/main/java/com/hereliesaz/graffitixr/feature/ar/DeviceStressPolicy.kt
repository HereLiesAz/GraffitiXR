package com.hereliesaz.graffitixr.feature.ar

/*
 * How the existing battery / heat safeties reach the heavy per-frame work. ArViewModel already folds
 * thermal pressure, OS power-save and the low-battery warning into `perceptionSystemThrottle`, and the
 * battery level into `batteryTier` (each behind its own Settings toggle). Before this, those signals
 * only moved the ARCore perception redraw floor and camera rate; the native relocalizer, the
 * standalone / co-op analyzers and standalone MiDaS ran at full rate whatever the device's state.
 * These pure decisions route the same signals to that work. Every consumer reads them per frame /
 * per pass, so a change (or a toggled safety) applies immediately.
 */

/** Combined device stress, from the safeties' outputs. */
internal enum class DeviceStress { NONE, MODERATE, HIGH }

/**
 * [DeviceStress.HIGH] under thermal / power-save / low-battery warning ([perceptionSystemThrottle])
 * or the low battery tier (≤ 15%); [DeviceStress.MODERATE] at the medium battery tier (≤ 30%).
 */
internal fun deviceStress(perceptionSystemThrottle: Boolean, batteryTier: Int): DeviceStress = when {
    perceptionSystemThrottle || batteryTier >= 2 -> DeviceStress.HIGH
    batteryTier == 1 -> DeviceStress.MODERATE
    else -> DeviceStress.NONE
}

/**
 * Native relocalizer pause between passes as `(lockedMs, huntMs)`. NONE keeps MobileGS's defaults
 * (200 / 60 ms ≈ 5 Hz locked, ≈ 16 Hz hunting).
 */
internal fun relocBackoffMs(stress: DeviceStress): Pair<Int, Int> = when (stress) {
    DeviceStress.NONE -> 200 to 60
    DeviceStress.MODERATE -> 400 to 120
    DeviceStress.HIGH -> 800 to 250
}

/** Minimum spacing of analyzed camera frames: none, ≤ 20 fps, ≤ 15 fps. */
internal fun stressFrameIntervalNs(stress: DeviceStress): Long = when (stress) {
    DeviceStress.NONE -> 0L
    DeviceStress.MODERATE -> 50_000_000L
    DeviceStress.HIGH -> 66_666_667L
}

/** Multiplier on the standalone MiDaS cadence (1 s tile-change / 2 s periodic at NONE). */
internal fun depthIntervalScale(stress: DeviceStress): Int = when (stress) {
    DeviceStress.NONE -> 1
    DeviceStress.MODERATE -> 2
    DeviceStress.HIGH -> 4
}

/**
 * Admission gate in front of a standalone camera analyzer (SphereSLAM tracking and the co-op peer
 * analyzer). Two independent limits, the stricter wins:
 *
 *  - **Device stress** ([stress]): frames are spaced at least [stressFrameIntervalNs] apart.
 *  - **Adaptive cadence** ([adaptiveCadence], Settings → Adaptive rate): once the analyzer's
 *    smoothed per-frame work exceeds the camera frame interval, CameraX's keep-only-latest already
 *    drops frames and the analysis thread runs flat out. The gate then spaces admitted frames
 *    `work / DUTY_BUDGET` apart, so the thread idles at least half the time — fewer pose updates,
 *    less heat. While work fits in a frame, nothing is dropped.
 *
 * The camera frame interval is estimated as the smallest gap between offered frames. Running flat
 * out, keep-only-latest hands the analyzer the newest frame that arrived while it worked, so the gaps
 * it sees are ⌊work / interval⌋ intervals — never more than the work. The estimate therefore stays at
 * or below the work whenever work exceeds a frame, and frames refused by the gate reveal the true
 * interval. A gap right after a refusal is one true interval, which lets the estimate follow a camera
 * that slowed down (dim-light AE).
 *
 * Inputs are volatile and read on every frame. [admit] / [onWorkDone] / [reset] are synchronized: one
 * gate outlives analyzer restarts, which run on different executors.
 */
internal class AnalysisFrameGate {
    @Volatile var stress: DeviceStress = DeviceStress.NONE
    @Volatile var adaptiveCadence: Boolean = false

    private var lastOfferedNs = Long.MIN_VALUE
    private var lastAdmittedNs = Long.MIN_VALUE
    private var previousRefused = false
    /** Camera frame interval estimate, ns; 0 = unknown. */
    private var frameIntervalNs = 0L
    /** Smoothed wall-clock work per admitted frame, ns; 0 = unknown. */
    private var workNs = 0L

    /** Whether to analyze the frame with camera timestamp [timestampNs]; a refused frame is just closed. */
    @Synchronized
    fun admit(timestampNs: Long): Boolean {
        val previous = lastOfferedNs
        lastOfferedNs = timestampNs
        if (previous == Long.MIN_VALUE || timestampNs <= previous || lastAdmittedNs == Long.MIN_VALUE) {
            // First frame, or the stream restarted / went backwards: start over from here.
            lastAdmittedNs = timestampNs
            previousRefused = false
            return true
        }
        val gap = timestampNs - previous
        if (frameIntervalNs == 0L || gap < frameIntervalNs) {
            frameIntervalNs = gap
        } else if (previousRefused) {
            // Right after a refusal the gap is one true interval: follow a camera that slowed down.
            frameIntervalNs += (gap - frameIntervalNs) / FRAME_INTERVAL_DRIFT
        }
        val interval = minIntervalNs()
        // A quarter frame of slack absorbs timestamp jitter, so a 15 fps cap over a 30 fps stream takes
        // every second frame rather than every third.
        if (timestampNs - lastAdmittedNs < interval - frameIntervalNs / 4) {
            previousRefused = true
            return false
        }
        previousRefused = false
        lastAdmittedNs = timestampNs
        return true
    }

    /** Report how long an admitted frame's analysis took (wall clock). */
    @Synchronized
    fun onWorkDone(durationNs: Long) {
        if (durationNs <= 0L) return
        workNs = if (workNs == 0L) durationNs else workNs + (durationNs - workNs) / WORK_SMOOTHING
    }

    @Synchronized
    fun reset() {
        lastOfferedNs = Long.MIN_VALUE
        lastAdmittedNs = Long.MIN_VALUE
        previousRefused = false
        frameIntervalNs = 0L
        workNs = 0L
    }

    /** The current minimum spacing of admitted frames, ns (0 = every frame). */
    @Synchronized
    fun minIntervalNs(): Long {
        val stressNs = stressFrameIntervalNs(stress)
        val adaptiveNs = if (adaptiveCadence && frameIntervalNs > 0L && workNs > frameIntervalNs) {
            (workNs / DUTY_BUDGET).toLong()
        } else 0L
        return maxOf(stressNs, adaptiveNs)
    }

    companion object {
        /** Largest share of wall time the analysis thread may spend working once over budget. */
        const val DUTY_BUDGET = 0.5
        private const val FRAME_INTERVAL_DRIFT = 8L
        private const val WORK_SMOOTHING = 8L
    }
}
