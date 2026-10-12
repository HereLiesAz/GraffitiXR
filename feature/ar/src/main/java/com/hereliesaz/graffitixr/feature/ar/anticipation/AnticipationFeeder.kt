package com.hereliesaz.graffitixr.feature.ar.anticipation

import com.hereliesaz.graffitixr.common.model.AnticipationSettings
import com.hereliesaz.sphereslam.AnticipatedView
import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.ln

/**
 * Feeds a SphereSLAM tracker the app's anticipated views of its pages (the fingerprint's teleological
 * layer, in every AR mode): the [HalfSphere] of [AnticipationSettings], throttled by the
 * [AnticipationGovernor]. SphereSLAM learns and searches them; the app decides which, how many and
 * when.
 *
 * Only the views the governor allows are rendered and handed over, farthest first (each next view the
 * one most unlike those before it), a few at a time on a background thread, re-reading the governor
 * between batches; the [Sink] is told which views to search, so throttling narrows the search at once
 * and lifting it reuses what was built. A new reference ([reset]) starts over.
 *
 * [onFrame] runs on the tracking thread, every frame; everything else is cheap and thread-safe.
 */
internal class AnticipationFeeder(
    private val sink: Sink,
    settings: AnticipationSettings,
    pressure: DevicePressureSource?,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "graffitixr-anticipation").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    },
    private val useOpenCv: Boolean = true,
) : AutoCloseable {

    /** Where views go: a `PhotosphereTrackingSession` (standalone) or a `SphereSlamTracker` (ARCore). */
    interface Sink {
        fun addViews(pageNo: Int, views: List<AnticipatedView>)
        fun setSearchable(ids: Set<String>?)
    }

    val governor = AnticipationGovernor(settings, pressure)

    /** The user's settings; apply from the next frame. */
    var settings: AnticipationSettings
        get() = governor.settings
        set(value) {
            governor.settings = value
        }

    /** The anticipation in force (for diagnostics). */
    val status: AnticipationStatus get() = governor.status

    /** Views handed to the sink so far, all pages. */
    @Volatile var viewsAdded = 0
        private set

    private val closed = AtomicBoolean(false)
    private val pending = AtomicBoolean(false)
    @Volatile private var camera: CameraIntrinsics? = null
    @Volatile private var applied: Pair<AnticipationSettings, AnticipationDecimation>? = null
    // Bumped by reset(): a build started before it never adds views of the old reference.
    @Volatile private var generation = 0

    // Executor-only.
    private class Page(val pageNo: Int, val luma: ByteArray, val width: Int, val height: Int) {
        var planKey: Pair<AnticipationSettings, CameraIntrinsics>? = null
        var plans: List<HalfSphere.Plan> = emptyList()
        val built = LinkedHashMap<String, HalfSphere.Site>()
    }
    private val pages = LinkedHashMap<Int, Page>()

    /** A new reference: forget every page and view (the tracker drops its views with its reference). */
    fun reset() {
        generation++
        val gen = generation
        applied = null
        submit {
            if (gen != generation) return@submit
            pages.clear()
        }
    }

    /** Page [pageNo] of the current reference ([luma], [width] × [height], tightly packed; copied). */
    fun setPage(pageNo: Int, luma: ByteArray, width: Int, height: Int) {
        require(luma.size == width * height)
        val copy = luma.copyOf()
        val gen = generation
        submit {
            if (gen != generation) return@submit
            pages[pageNo] = Page(pageNo, copy, width, height)
        }
        applied = null // build its views at the next frame
    }

    /**
     * Per frame, on the tracking thread: [workMs] this frame's processing (for compute pressure),
     * [camera] the matching camera (views are rendered for it; null until known). Starts a build or
     * narrows the search when the anticipation changed.
     */
    fun onFrame(nowMs: Long, workMs: Float, timestampNs: Long, camera: CameraIntrinsics?) {
        if (closed.get()) return
        governor.onFrame(workMs, timestampNs)
        val status = governor.update(nowMs)
        if (camera == null) return
        val key = status.max.grid() to status.decimation
        if (camera == this.camera && key == applied) return
        this.camera = camera
        applied = key
        if (pending.compareAndSet(false, true)) {
            submit {
                pending.set(false)
                extend()
            }
        }
    }

    /** Executor: hand over the allowed views not yet built, [BATCH] at a time, then set the search. */
    private fun extend() {
        val gen = generation
        while (!closed.get() && gen == generation) {
            val cam = camera ?: return
            val status = governor.status
            val max = status.max.grid()
            val keep = status.decimation
            var batch: List<HalfSphere.Plan> = emptyList()
            var page: Page? = null
            for (p in pages.values) {
                planFor(p, max, cam)
                val todo = p.plans.filter { it.label !in p.built && keep.keeps(it.site, max) }
                if (todo.isNotEmpty()) {
                    batch = todo.take(BATCH)
                    page = p
                    break
                }
            }
            val target = page ?: break
            val views = batch.map { plan ->
                val preview = HalfSphere.render(target.luma, target.width, target.height, plan, useOpenCv)
                target.built[plan.label] = plan.site
                AnticipatedView(
                    id = viewId(target.pageNo, plan.label),
                    luma = preview.luma,
                    width = preview.width,
                    height = preview.height,
                    pageFromView = preview.pageFromView,
                    cameraFromPage = FloatArray(9) { preview.cameraFromPage[it].toFloat() },
                    scale = preview.scale.toFloat(),
                )
            }
            if (gen != generation) return
            sink.addViews(target.pageNo, views)
            viewsAdded += views.size
        }
        if (gen != generation) return
        // Search what the anticipation allows, of what was built for the current maximum and camera.
        val status = governor.status
        val max = status.max.grid()
        val ids = HashSet<String>()
        for (p in pages.values) {
            val current = p.plans.associateBy { it.label }
            for ((label, site) in p.built) if (label in current && status.decimation.keeps(site, max)) ids += viewId(p.pageNo, label)
        }
        sink.setSearchable(ids)
    }

    /** [p]'s plans for [max] and [cam], farthest first. */
    private fun planFor(p: Page, max: AnticipationSettings, cam: CameraIntrinsics) {
        val key = max to cam
        if (p.planKey == key) return
        p.planKey = key
        p.plans = HalfSphere.prioritized(HalfSphere.plans(p.width, p.height, cam, max), ::priorityCost)
    }

    private fun submit(task: () -> Unit) {
        if (closed.get()) return
        try {
            executor.execute {
                if (closed.get()) return@execute
                runCatching(task).onFailure { timber.log.Timber.w(it, "Anticipation build failed") }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) executor.shutdownNow()
    }

    companion object {
        /** Views rendered and handed over together; the governor is read again between batches. */
        const val BATCH = 4

        /** How much a turn about the optical axis counts in [priorityCost]: KPM's features turn with it. */
        const val TURN_WEIGHT = 0.25

        /** Degrees of view angle that weigh as much as a doubling of zoom. */
        const val DEG_PER_OCTAVE = 30.0

        /** Degrees of view angle that weigh as much as aims a whole frame apart (no overlap). */
        const val DEG_PER_FRAME = 30.0

        fun viewId(pageNo: Int, label: String) = "p$pageNo $label"

        /** Only the grid fields: weights do not change which views exist. */
        private fun AnticipationSettings.grid() = AnticipationSettings(distance, latitude, longitude, roll, 0f, 0f, 0f)

        /**
         * How far apart two views are for KPM, which decides the build order: the angle between their
         * optical axes (tilt and side, what KPM's features do not survive), plus [TURN_WEIGHT] of the
         * full angle between them (the turn, which they largely do), plus [DEG_PER_OCTAVE] per doubling
         * of zoom, plus [DEG_PER_FRAME] per frame width between their aim points (the smaller frame's:
         * two close-ups of different parts of the wall share nothing).
         */
        fun priorityCost(a: HalfSphere.Pose, b: HalfSphere.Pose): Double {
            var dot = 0.0
            for (k in 0..2) dot += a.cameraFromPage[6 + k] * b.cameraFromPage[6 + k]
            val axis = Math.toDegrees(acos(dot.coerceIn(-1.0, 1.0)))
            val apart = hypot(a.aimX - b.aimX, a.aimY - b.aimY) / minOf(a.footprint, b.footprint)
            return axis + TURN_WEIGHT * HalfSphere.viewAngleDeg(a.cameraFromPage, b.cameraFromPage) +
                DEG_PER_OCTAVE * abs(ln(a.scale / b.scale) / ln(2.0)) + DEG_PER_FRAME * apart
        }
    }
}
