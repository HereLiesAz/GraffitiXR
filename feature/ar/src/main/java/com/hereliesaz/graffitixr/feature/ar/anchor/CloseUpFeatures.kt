package com.hereliesaz.graffitixr.feature.ar.anchor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.model.AnticipationSettings
import com.hereliesaz.graffitixr.feature.ar.anchor.StandaloneFingerprintBuilder.DetectedFeatures
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
 * scale passes.
 */
internal object CloseUpFeatures {

    /** Half-spheres, from the capture distance in: zooms 6, 3 and 2 by default. */
    const val DISTANCE = AnticipationSettings.DEFAULT_DISTANCE

    /** Smaller zooms are covered by the relocalizer's own 0.5x/2x passes and ORB's pyramid. */
    const val MIN_ZOOM = 2f

    /** A close-up's long side: about a live camera frame, so marks appear at the size it sees them. */
    const val MAX_VIEW_SIDE = 960

    /** At most this many close-up features, and never more than the capture's own count. */
    const val MAX_EXTRA = 2000

    /** At least this many per close-up, so the smallest crops still contribute. */
    const val MIN_PER_VIEW = 30

    /** Crops at or below this zoom are the "quick set": learned first, installed before the rest. */
    const val QUICK_MAX_ZOOM = 3f

    /** A close-up: the photo's crop at ([x], [y]), [w] × [h] pixels, seen [zoom] times nearer. */
    data class View(val x: Int, val y: Int, val w: Int, val h: Int, val zoom: Float)

    /**
     * The close-ups of a [width] × [height] photo: for each half-sphere i = 1, 2, … whose zoom
     * `distance / i` is at least [MIN_ZOOM], a `ceil(distance / i)`-per-side grid of crops `1 / zoom`
     * of the photo, centred on the grid's cells and kept inside the photo.
     */
    fun views(width: Int, height: Int, distance: Int = DISTANCE): List<View> {
        if (width <= 0 || height <= 0) return emptyList()
        val settings = AnticipationSettings(distance = distance)
        val out = ArrayList<View>()
        // Coarsest first: the broad, low-zoom crops are the views a search from normal distance
        // actually sees, so they are the most integral and are learned (and installed) first.
        for (i in (distance - 1) downTo 1) {
            val zoom = distance.toFloat() / i
            if (zoom < MIN_ZOOM) continue
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

    /** Features per close-up: the capture's count (at most [MAX_EXTRA]) shared out, at least [MIN_PER_VIEW]. */
    fun perViewBudget(baseRows: Int, viewCount: Int): Int =
        if (viewCount <= 0) 0 else max(MIN_PER_VIEW, min(baseRows, MAX_EXTRA) / viewCount)

    /** The close-up's enlarged size: the photo's long side, at most [MAX_VIEW_SIDE]. */
    fun outputSize(view: View, photoWidth: Int, photoHeight: Int): Pair<Int, Int> {
        val side = min(MAX_VIEW_SIDE, max(photoWidth, photoHeight)).toFloat()
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
        detect: (Bitmap, Int) -> DetectedFeatures?,
    ): DetectedFeatures? {
        val views = views(photo.width, photo.height)
        val quickCount = views.count { it.zoom <= QUICK_MAX_ZOOM }
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
                val crop = Bitmap.createBitmap(photo, v.x, v.y, v.w, v.h)
                val (outW, outH) = outputSize(v, photo.width, photo.height)
                val view = Bitmap.createScaledBitmap(crop, outW, outH, true)
                try {
                    detect(view, per)?.let { parts += mapBack(truncated(it, per), v, outW, outH) }
                } finally {
                    if (view !== crop) view.recycle()
                    if (crop !== photo) crop.recycle()
                }
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
