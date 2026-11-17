package com.hereliesaz.graffitixr.feature.ar.anchor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.model.AnticipationSettings
import com.hereliesaz.graffitixr.feature.ar.anchor.StandaloneFingerprintBuilder.DetectedFeatures
import com.hereliesaz.graffitixr.feature.ar.anticipation.HalfSphere
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The doodle's close-ups: features of the capture photo as the camera will see them up close,
 * added to the doodle beside the capture's own.
 *
 * A capture spreads its ~1500 features over the whole wall, so a close-up of one corner meets only
 * the few that fell there, and at a size the capture never saw them at (SuperPoint has no scale
 * invariance; ORB's pyramid spans about 3.6x). The same half-spheres as the anticipation settings
 * (`AnticipationSettings.DEFAULT_DISTANCE` of them, from the capture distance in) give the zooms:
 * zoom `distance / i` on half-sphere i, over a grid of `ceil(distance / i)` aims per side
 * ([AnticipationSettings.aimsPerSide]), square-on (the relocalizer turns a live frame square-on by
 * the sensors). Each close-up is a crop of the photo enlarged to about a live frame's size, run through
 * the base doodle's own detector; its features map back to the photo's pixels, so the builder
 * places them on the wall exactly like its own. Zooms under [MIN_ZOOM] are left to the matcher's own
 * scale passes, and zooms over the cap ([NON_METRIC_MAX_ZOOM], or [maxZoomFor] when the capture
 * distance is known in metres) are dropped: nearer than the lens can focus, the camera never sees
 * the wall that sharp, so those crops (most of them, at zoom 6) were wasted work.
 */
internal object CloseUpFeatures {

    /** Half-spheres, from the capture distance in: zooms 6, 3 and 2 before the [NON_METRIC_MAX_ZOOM] cap. */
    const val DISTANCE = AnticipationSettings.DEFAULT_DISTANCE

    /** Smaller zooms are covered by the relocalizer's own 0.5x/2x passes and ORB's pyramid. */
    const val MIN_ZOOM = 2f

    /** A close-up's long side: about a live camera frame, so marks appear at the size it sees them. */
    const val MAX_VIEW_SIDE = 960

    /**
     * A close-up's long side when the detector is SuperPoint: its network takes at most 640 px
     * (`SuperPointDetector::kMaxInputSide`) and downsizes anything larger before inference, so
     * enlarging a crop past that only costs the scaling and the copy into native.
     */
    const val SUPERPOINT_VIEW_SIDE = 640

    /**
     * How long, in milliseconds, the crops past the quick set may keep starting once the quick set is
     * learned. A build has to be sat through (there is no foreground service to carry it), and an
     * inference costs seconds on a slow phone, so the tail is bounded by time, not by count: a fast
     * phone finishes every crop, a slow one stops early with what it has.
     */
    const val FINE_BUDGET_MS = 45_000L

    /** At most this many close-up features, and never more than the capture's own count. */
    const val MAX_EXTRA = 2000

    /** At least this many per close-up, so the smallest crops still contribute. */
    const val MIN_PER_VIEW = 30

    /** Crops at or below this zoom are the "quick set": learned first, installed before the rest. */
    const val QUICK_MAX_ZOOM = 2f

    /**
     * Without a metric capture distance, zoom at most this much: a 40 cm page captured from about
     * 30 cm is in focus at a third of that on most phones, not at a sixth.
     */
    const val NON_METRIC_MAX_ZOOM = 3f

    /**
     * The largest useful zoom for a photo captured from [captureDistanceMeters] by a lens that focuses
     * no nearer than [minFocusMeters] (with [HalfSphere.MIN_FOCUS_MARGIN]); [NON_METRIC_MAX_ZOOM] when
     * the distance is unknown.
     */
    fun maxZoomFor(
        captureDistanceMeters: Double?,
        minFocusMeters: Double = HalfSphere.DEFAULT_MIN_FOCUS_M,
    ): Float =
        if (captureDistanceMeters == null || !captureDistanceMeters.isFinite() || captureDistanceMeters <= 0.0) NON_METRIC_MAX_ZOOM
        else (captureDistanceMeters / (minFocusMeters * HalfSphere.MIN_FOCUS_MARGIN)).toFloat()

    /** A close-up: the photo's crop at ([x], [y]), [w] × [h] pixels, seen [zoom] times nearer. */
    data class View(val x: Int, val y: Int, val w: Int, val h: Int, val zoom: Float)

    /**
     * The close-ups of a [width] × [height] photo: for each half-sphere i = 1, 2, … whose zoom
     * `distance / i` is at least [MIN_ZOOM] and at most [maxZoom], a `ceil(distance / i)`-per-side grid
     * of crops `1 / zoom` of the photo, centred on the grid's cells and kept inside the photo.
     */
    fun views(width: Int, height: Int, distance: Int = DISTANCE, maxZoom: Float = NON_METRIC_MAX_ZOOM): List<View> {
        if (width <= 0 || height <= 0) return emptyList()
        val settings = AnticipationSettings(distance = distance)
        val out = ArrayList<View>()
        // Coarsest first: the broad, low-zoom crops are the views a search from normal distance
        // actually sees, so they are the most integral and are learned (and installed) first.
        for (i in (distance - 1) downTo 1) {
            val zoom = distance.toFloat() / i
            if (zoom < MIN_ZOOM || zoom > maxZoom * (1 + 1e-6f)) continue
            val n = settings.aimsPerSide(i - 1)
            val w = max(1, (width / zoom).roundToInt())
            val h = max(1, (height / zoom).roundToInt())
            for (r in 0 until n) for (c in 0 until n) {
                val cx = (c + 0.5f) / n * width
                val cy = (r + 0.5f) / n * height
                val x = (cx - w / 2f).roundToInt().coerceIn(0, width - w)
                val y = (cy - h / 2f).roundToInt().coerceIn(0, height - h)
                out += View(x, y, w, h, zoom)
            }
        }
        return out
    }

    /** How many of the photo's own features ([positions], `[u0, v0, u1, v1, …]`) fall inside [view]. */
    fun featuresIn(view: View, positions: FloatArray): Int {
        var n = 0
        var k = 0
        while (k + 1 < positions.size) {
            val x = positions[k]
            val y = positions[k + 1]
            if (x >= view.x && x < view.x + view.w && y >= view.y && y < view.y + view.h) n++
            k += 2
        }
        return n
    }

    /**
     * [views] in the order to detect them. The crops up to [NON_METRIC_MAX_ZOOM] (the ones every build
     * has always made) keep their place; the finer ones, which exist only when the capture distance is
     * known, drop the crops over wall where the photo's own detection ([basePositions]) found nothing
     * (no marks, so nothing a close-up could match) and go densest first, so a bounded build spends
     * its time over the marks. Null [basePositions] leaves [views] as given.
     */
    fun prioritized(views: List<View>, basePositions: FloatArray?): List<View> {
        if (basePositions == null) return views
        val kept = ArrayList<View>(views.size)
        val fine = ArrayList<Pair<View, Int>>()
        for (v in views) {
            if (v.zoom <= NON_METRIC_MAX_ZOOM * (1 + 1e-6f)) kept += v
            else featuresIn(v, basePositions).let { if (it > 0) fine += v to it }
        }
        // sortedByDescending is stable: equally dense crops stay in grid order.
        fine.sortedByDescending { it.second }.mapTo(kept) { it.first }
        return kept
    }

    /**
     * When the crops past the quick set must stop starting: [budgetMs] after the quick set is learned
     * (after the first call when there is none), by [now] in milliseconds. A crop under way is finished.
     */
    class FineBudget(private val budgetMs: Long, private val now: () -> Long) {
        private var deadline = Long.MAX_VALUE

        /** The quick set (or, with none, the build's start) is done: the tail's clock starts. */
        fun start() {
            if (deadline == Long.MAX_VALUE) deadline = now() + budgetMs
        }

        /** True when a crop at [zoom] must not start: it is past the quick set and the clock ran out. */
        fun exhausted(zoom: Float): Boolean = zoom > QUICK_MAX_ZOOM && now() >= deadline
    }

    /** Features per close-up: the capture's count (at most [MAX_EXTRA]) shared out, at least [MIN_PER_VIEW]. */
    fun perViewBudget(baseRows: Int, viewCount: Int): Int =
        if (viewCount <= 0) 0 else max(MIN_PER_VIEW, min(baseRows, MAX_EXTRA) / viewCount)

    /** The close-up's enlarged size: the photo's long side, at most [maxSide] ([MAX_VIEW_SIDE], or [SUPERPOINT_VIEW_SIDE]). */
    fun outputSize(view: View, photoWidth: Int, photoHeight: Int, maxSide: Int = MAX_VIEW_SIDE): Pair<Int, Int> {
        val side = min(maxSide, max(photoWidth, photoHeight)).toFloat()
        val s = side / max(view.w, view.h)
        return max(1, (view.w * s).roundToInt()) to max(1, (view.h * s).roundToInt())
    }

    /** [detected] (in a [outW] × [outH] close-up of [view]) with positions back in the photo's pixels. */
    fun mapBack(detected: DetectedFeatures, view: View, outW: Int, outH: Int): DetectedFeatures {
        val sx = view.w.toFloat() / outW
        val sy = view.h.toFloat() / outH
        val p = FloatArray(detected.positions.size) { k ->
            // Pixel centres: photo x = view.x + (u + 1/2) · sx − 1/2.
            if (k % 2 == 0) view.x + (detected.positions[k] + 0.5f) * sx - 0.5f
            else view.y + (detected.positions[k] + 0.5f) * sy - 0.5f
        }
        return detected.copy(positions = p)
    }

    /** The first [n] features of [detected] (detectors list the strongest first). */
    fun truncated(detected: DetectedFeatures, n: Int): DetectedFeatures {
        if (detected.rows <= n) return detected
        val rowBytes = detected.descriptorsData.size / detected.rows
        return detected.copy(
            positions = detected.positions.copyOf(n * 2),
            descriptorsData = detected.descriptorsData.copyOf(n * rowBytes),
            rows = n,
        )
    }

    /** [parts] one after another, or null when none has a feature; all must share a descriptor layout. */
    fun concat(parts: List<DetectedFeatures>): DetectedFeatures? {
        val nonEmpty = parts.filter { it.rows > 0 }
        if (nonEmpty.isEmpty()) return null
        val first = nonEmpty.first()
        val same = nonEmpty.filter { it.cols == first.cols && it.type == first.type }
        val rows = same.sumOf { it.rows }
        val positions = FloatArray(rows * 2)
        val descriptors = ByteArray(same.sumOf { it.descriptorsData.size })
        var pAt = 0; var dAt = 0
        for (d in same) {
            d.positions.copyInto(positions, pAt); pAt += d.positions.size
            d.descriptorsData.copyInto(descriptors, dAt); dAt += d.descriptorsData.size
        }
        return DetectedFeatures(positions, descriptors, rows, first.cols, first.type)
    }

    /**
     * The close-up features of [photo] for a doodle of [baseRows] features, found by [detect]
     * (the base doodle's own detector, asked for at most the given count), positions in the
     * photo's pixels. Null when none was found.
     */
    fun detect(
        photo: Bitmap,
        baseRows: Int,
        /**
         * Called once, on this thread, with the features of the quick set (every crop at or below
         * [QUICK_MAX_ZOOM]) as soon as they are done, so relocalization can start while the finer
         * crops are still being learned. Null when the quick set found nothing.
         */
        onQuickSet: ((DetectedFeatures?) -> Unit)? = null,
        /** Stage number of the quick set; the fine crops are the next one. */
        firstStage: Int = 1,
        /** Stages in the whole build, for "stage n of N" (the caller may have stages of its own before this). */
        stages: Int = 2,
        /** Steps the caller already finished (counted in the overall done/total). */
        stepsBefore: Int = 0,
        /**
         * Highest zoom worth learning: [maxZoomFor] the capture distance when the caller knows it in
         * metres (an ARCore capture onto a tracked plane), else the conservative [NON_METRIC_MAX_ZOOM].
         */
        maxZoom: Float = NON_METRIC_MAX_ZOOM,
        /**
         * The photo's own features (`[u0, v0, u1, v1, …]`, in the photo's pixels): crops past
         * [NON_METRIC_MAX_ZOOM] over wall where it found none are skipped, the densest go first
         * ([prioritized]). Null skips nothing.
         */
        basePositions: FloatArray? = null,
        /** The crop's long side, see [outputSize]: [SUPERPOINT_VIEW_SIDE] for SuperPoint. */
        viewSide: Int = MAX_VIEW_SIDE,
        /** How long the crops past the quick set may keep starting, see [FineBudget]. */
        budgetMs: Long = FINE_BUDGET_MS,
        now: () -> Long = { System.nanoTime() / 1_000_000L },
        detect: (Bitmap, Int) -> DetectedFeatures?,
    ): DetectedFeatures? {
        val views = prioritized(views(photo.width, photo.height, maxZoom = maxZoom), basePositions)
        val quickCount = views.count { it.zoom <= QUICK_MAX_ZOOM }
        val budget = FineBudget(budgetMs, now)
        if (quickCount == 0) budget.start()
        val per = perViewBudget(baseRows, views.size)
        val parts = ArrayList<DetectedFeatures>(views.size)
        // The slow part of building a target: the artist sees it count down.
        val total = stepsBefore + views.size
        fun report(i: Int) {
            val inQuick = i < quickCount || quickCount == views.size
            val stageTotal = if (inQuick) quickCount else views.size - quickCount
            val stageDone = if (inQuick) i else i - quickCount
            DoodleBuildProgress.set(
                DoodleBuildProgress.Step(
                    stage = if (inQuick) firstStage else firstStage + 1, stages = stages,
                    stageDone = stageDone, stageTotal = stageTotal,
                    done = stepsBefore + i, total = total,
                )
            )
        }
        report(0)
        try {
            for ((i, v) in views.withIndex()) {
                // Out of time: stop with what was learned (the views are coarsest first, densest first).
                if (budget.exhausted(v.zoom)) break
                val crop = Bitmap.createBitmap(photo, v.x, v.y, v.w, v.h)
                val (outW, outH) = outputSize(v, photo.width, photo.height, viewSide)
                val view = Bitmap.createScaledBitmap(crop, outW, outH, true)
                try {
                    detect(view, per)?.let { parts += mapBack(truncated(it, per), v, outW, outH) }
                } finally {
                    if (view !== crop) view.recycle()
                    if (crop !== photo) crop.recycle()
                }
                if (i + 1 == quickCount) budget.start()
                if (i + 1 == quickCount && i + 1 < views.size) onQuickSet?.invoke(concat(parts))
                // After the quick set's hand-off, so the pill moves to stage 2/3 as its crops start.
                if (i + 1 < views.size) report(i + 1)
                else DoodleBuildProgress.set(
                    DoodleBuildProgress.Step(
                        stage = if (quickCount == views.size) firstStage else firstStage + 1, stages = stages,
                        stageDone = if (quickCount == views.size) views.size else views.size - quickCount,
                        stageTotal = if (quickCount == views.size) views.size else views.size - quickCount,
                        done = total, total = total,
                    )
                )
            }
        } finally {
            DoodleBuildProgress.set(null)
        }
        return concat(parts)
    }
}
