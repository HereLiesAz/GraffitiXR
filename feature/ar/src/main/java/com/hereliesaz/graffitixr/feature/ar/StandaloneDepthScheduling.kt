package com.hereliesaz.graffitixr.feature.ar

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.floor

/*
 * Off-thread scheduling for the standalone analyzer's optional MiDaS depth (Phase 2 of the
 * spherical-coverage map). MiDaS runs on LiteRT's GPU delegate since SphereSLAM 0.24.3 (tens of ms)
 * but falls back to the XNNPACK CPU path (hundreds of ms) where the GPU delegate is unavailable, and
 * the estimator blocks its caller until its own `SphereSLAM-depth` thread finishes. Run inline on the
 * single CameraX analysis thread it froze tracking for that long every time, and the next frame's
 * KPM observation then aged past the session's observation-age gate (STALE_OBSERVATION flicker).
 * These pieces keep that work off the analysis thread and rare:
 *  - [StandaloneDepthCadence] decides WHEN a run is due (view-direction tile change or atlas growth,
 *    else a slow periodic refresh);
 *  - [DropIfBusyWorker] runs it on its own thread, dropping a request while one is in flight
 *    (never queueing a backlog of stale frames), and hands the result back to be applied on the
 *    analysis thread;
 *  - [DepthLumaDownsampler] copies only the small luma grid the 256x256 model actually consumes.
 * All pure / injectable so the policy is unit-tested without a device.
 */

/**
 * When the next depth inference is due. Not thread-safe: owned by the analysis thread.
 *
 * A run is due when
 *  - the camera has moved to a new coarse view-direction tile (or the atlas grew a page) since the
 *    last run AND at least [minIntervalMs] has passed, or
 *  - [maxIntervalMs] has passed regardless (slow refresh while the view is static).
 * The first request is due immediately.
 */
internal class StandaloneDepthCadence(
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val maxIntervalMs: Long = DEFAULT_MAX_INTERVAL_MS,
    private val tileDegrees: Float = DEFAULT_TILE_DEGREES,
) {
    init {
        require(minIntervalMs >= 0L && maxIntervalMs >= minIntervalMs)
        require(tileDegrees > 0f && tileDegrees.isFinite())
    }

    private var lastRunMs = Long.MIN_VALUE
    private var lastRunTile: Long? = null
    private var currentTile: Long? = null
    private var atlasGrew = false

    /** Latest keyframe view direction; quantized to a [tileDegrees] heading x elevation tile. */
    fun onViewDirection(headingDeg: Float, elevationDeg: Float) {
        if (!headingDeg.isFinite() || !elevationDeg.isFinite()) return
        currentTile = tileKey(headingDeg, elevationDeg)
    }

    /** A new wall atlas page was grown — new surface worth depth. */
    fun onAtlasGrowth() {
        atlasGrew = true
    }

    /**
     * Whether a run is due at [nowMs]. Does not consume the slot; call [markRun] when one starts.
     * [intervalScale] (≥ 1) stretches both intervals under device stress ([depthIntervalScale]).
     */
    fun isDue(nowMs: Long, intervalScale: Int = 1): Boolean {
        if (lastRunMs == Long.MIN_VALUE) return true
        val scale = intervalScale.coerceAtLeast(1)
        val elapsed = nowMs - lastRunMs
        if (elapsed >= maxIntervalMs * scale) return true
        val changed = atlasGrew || (currentTile != null && currentTile != lastRunTile)
        return changed && elapsed >= minIntervalMs * scale
    }

    /** A run actually started at [nowMs] (it was not dropped as busy). */
    fun markRun(nowMs: Long) {
        lastRunMs = nowMs
        lastRunTile = currentTile
        atlasGrew = false
    }

    fun reset() {
        lastRunMs = Long.MIN_VALUE
        lastRunTile = null
        currentTile = null
        atlasGrew = false
    }

    private fun tileKey(headingDeg: Float, elevationDeg: Float): Long {
        val h = ((headingDeg % 360f) + 360f) % 360f
        // Over a floor or under a ceiling the axis heading is sensor noise: binning it would call every
        // frame a new tile and run MiDaS at the minimum interval. The polar caps are one tile each.
        val hBin = if (abs(elevationDeg) >= POLAR_CAP_DEG) 0L else floor(h / tileDegrees).toLong()
        val eBin = floor(elevationDeg.coerceIn(-90f, 90f) / tileDegrees).toLong()
        return (hBin shl 32) or (eBin and 0xFFFFFFFFL)
    }

    companion object {
        /** Tile-change runs are spaced at least this far apart (~3 MiDaS runs of budget). */
        const val DEFAULT_MIN_INTERVAL_MS = 1_000L

        /** Periodic refresh while the view stays in one tile. */
        const val DEFAULT_MAX_INTERVAL_MS = 2_000L

        /** At or beyond this |elevation| the view is in a polar cap, one tile whatever the heading. */
        const val POLAR_CAP_DEG = 60f

        /** Coarse view-direction tile; MiDaS output is downscaled to 128px, so finer is wasted. */
        const val DEFAULT_TILE_DEGREES = 30f
    }
}

/**
 * Runs at most one task at a time on [executor] and drops new submissions while one is in flight.
 * The latest result is parked until the owner [takeResult]s it, so the owner applies it on its own
 * thread; results finishing after [close] are discarded.
 */
internal class DropIfBusyWorker<R : Any>(
    private val executor: Executor,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    private val busy = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val result = AtomicReference<R?>(null)

    val isBusy: Boolean get() = busy.get()

    /**
     * Submit [task] unless one is already running (or this is closed). Returns whether it was
     * accepted. A null result from [task] means "nothing to apply". Exceptions from [task] (and an
     * executor rejection) go to [onError]; the worker never wedges busy.
     */
    fun trySubmit(task: () -> R?, onError: (Throwable) -> Unit = {}): Boolean {
        if (closed.get()) return false
        if (!busy.compareAndSet(false, true)) return false
        try {
            executor.execute {
                try {
                    val r = if (closed.get()) null else task()
                    if (r != null && !closed.get()) result.set(r)
                } catch (t: Throwable) {
                    onError(t)
                } finally {
                    busy.set(false)
                }
            }
        } catch (e: RejectedExecutionException) {
            // Executor already shut down (analyzer closing): treat as a dropped request.
            busy.set(false)
            onError(e)
            return false
        }
        return true
    }

    /** The latest finished result, or null; clears it. */
    fun takeResult(): R? = if (closed.get()) null else result.getAndSet(null)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        result.set(null)
        onClose()
    }
}

/**
 * The standalone analyzer's depth worker: one dedicated background-priority daemon thread
 * (`sphereslam-standalone-depth`), shut down when the worker is closed with the analyzer.
 */
internal fun <R : Any> newStandaloneDepthWorker(): DropIfBusyWorker<R> {
    val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(
            {
                // Below the camera/analysis threads: depth is enrichment, tracking is not.
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            },
            "sphereslam-standalone-depth",
        ).apply { isDaemon = true }
    }
    return DropIfBusyWorker(executor, onClose = { executor.shutdown() })
}

/**
 * The luma grid handed to MiDaS: a box-averaged integer-stride downsample of the display-upright
 * frame whose short side stays >= [minShortSide]. MiDaS squashes its input to 256x256 anyway, so the
 * full 1-2 MP frame (and its ARGB bitmap) was pure copy/allocation cost on the analysis thread.
 */
internal object DepthLumaDownsampler {
    const val DEFAULT_MIN_SHORT_SIDE = 256

    class Grid(val bytes: ByteArray, val width: Int, val height: Int)

    fun stride(width: Int, height: Int, minShortSide: Int = DEFAULT_MIN_SHORT_SIDE): Int =
        maxOf(1, minOf(width, height) / minShortSide)

    fun downsample(
        luma: ByteArray,
        width: Int,
        height: Int,
        minShortSide: Int = DEFAULT_MIN_SHORT_SIDE,
    ): Grid? {
        if (width <= 0 || height <= 0 || luma.size < width * height) return null
        val s = stride(width, height, minShortSide)
        if (s == 1) return Grid(luma.copyOf(width * height), width, height)
        val ow = width / s
        val oh = height / s
        val out = ByteArray(ow * oh)
        val area = s * s
        for (oy in 0 until oh) {
            val y0 = oy * s
            for (ox in 0 until ow) {
                val x0 = ox * s
                var sum = 0
                for (dy in 0 until s) {
                    val row = (y0 + dy) * width + x0
                    for (dx in 0 until s) sum += luma[row + dx].toInt() and 0xFF
                }
                out[oy * ow + ox] = (sum / area).toByte()
            }
        }
        return Grid(out, ow, oh)
    }
}
