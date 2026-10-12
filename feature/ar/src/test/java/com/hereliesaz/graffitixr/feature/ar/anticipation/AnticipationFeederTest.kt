package com.hereliesaz.graffitixr.feature.ar.anticipation

import com.hereliesaz.graffitixr.common.model.AnticipationSettings
import com.hereliesaz.sphereslam.AnticipatedView
import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/** [AnticipationFeeder]: what reaches the tracker, and when. */
class AnticipationFeederTest {

    private val w = 400
    private val h = 300
    private val camera = CameraIntrinsics(260f, 260f, 159.5f, 119.5f, 320, 240)
    private val small = AnticipationSettings(1, 4, 1, 1, 0f, 0f, 0f) // 5 views, never throttled by pressure

    /** Runs nothing until [drain]: the test plays the background thread. */
    private class Queue : AbstractExecutorService() {
        val tasks = ArrayList<Runnable>()
        var shut = false
        fun drain() { while (tasks.isNotEmpty()) tasks.removeAt(0).run() }
        override fun execute(command: Runnable) { tasks += command }
        override fun shutdown() { shut = true }
        override fun shutdownNow(): MutableList<Runnable> { shut = true; return tasks.toMutableList().also { tasks.clear() } }
        override fun isShutdown() = shut
        override fun isTerminated() = shut
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private class Sink : AnticipationFeeder.Sink {
        val added = ArrayList<Pair<Int, List<AnticipatedView>>>()
        var searched: Set<String>? = null
        var searchCalls = 0
        override fun addViews(pageNo: Int, views: List<AnticipatedView>) { added += pageNo to views }
        override fun setSearchable(ids: Set<String>?) { searched = ids; searchCalls++ }
        val ids get() = added.flatMap { it.second }.map { it.id }
    }

    private fun feeder(sink: Sink, q: Queue, s: AnticipationSettings = small) =
        AnticipationFeeder(sink, s, null, q, useOpenCv = false)

    @Test
    fun `a page's views go over in batches, farthest first, then become searchable`() {
        val sink = Sink(); val q = Queue()
        val f = feeder(sink, q)
        f.setPage(0, ByteArray(w * h), w, h)
        // No camera yet: nothing built.
        f.onFrame(0, 1f, 0, null)
        q.drain()
        assertTrue(sink.added.isEmpty())
        f.onFrame(10, 1f, 1, camera)
        q.drain()
        assertEquals(listOf(4, 1), sink.added.map { it.second.size })
        assertTrue(sink.added.all { it.first == 0 })
        assertEquals(5, sink.ids.distinct().size)
        assertTrue(sink.ids.all { it.startsWith("p0 ") })
        assertEquals(sink.ids.toSet(), sink.searched)
        assertEquals(5, f.viewsAdded)
        // Two half-spheres: the near one at 2 × 2 aims, 4 × 5 + 5 views.
        f.settings = small.copy(distance = 2)
        f.onFrame(15, 1f, 2, camera); q.drain()
        assertEquals(25, sink.searched!!.size)
        assertEquals(25, sink.ids.distinct().size)
        // The same frame again: nothing new.
        f.onFrame(20, 1f, 3, camera)
        assertTrue(q.tasks.isEmpty())
        // Each view carries a 3×3 rotation, a scale and its pixels.
        val v = sink.added.first().second.first()
        assertEquals(v.width * v.height, v.luma.size)
        assertEquals(9, v.cameraFromPage.size)
    }

    @Test
    fun `lowering the resolution narrows the search and builds nothing, raising reuses`() {
        val sink = Sink(); val q = Queue()
        val f = feeder(sink, q)
        f.setPage(0, ByteArray(w * h), w, h)
        f.onFrame(0, 1f, 0, camera); q.drain()
        val built = sink.added.size
        // Fewer meridians: a new maximum re-plans, and views it shares with the old (same label) are kept.
        f.settings = small.copy(latitude = 2)
        f.onFrame(10, 1f, 1, camera); q.drain()
        assertEquals(built, sink.added.size) // every 2-meridian view was already built (0° and 180°)
        assertEquals(small.copy(latitude = 2).maxViews, sink.searched!!.size)
        // Back up: nothing rebuilt.
        f.settings = small
        f.onFrame(20, 1f, 2, camera); q.drain()
        assertEquals(built, sink.added.size)
        assertEquals(5, sink.searched!!.size)
        // Off: nothing searched.
        f.settings = small.copy(distance = 0)
        f.onFrame(30, 1f, 3, camera); q.drain()
        assertEquals(emptySet<String>(), sink.searched)
    }

    @Test
    fun `a reset drops the old reference's pages, and a build started before it adds nothing`() {
        val sink = Sink(); val q = Queue()
        val f = feeder(sink, q)
        f.setPage(0, ByteArray(w * h), w, h)
        f.onFrame(0, 1f, 0, camera)
        f.reset()
        q.drain()
        assertTrue(sink.added.isEmpty())
        // A new page after the reset builds as usual.
        f.setPage(1, ByteArray(w * h), w, h)
        f.onFrame(10, 1f, 1, camera); q.drain()
        assertTrue(sink.added.isNotEmpty() && sink.added.all { it.first == 1 })
        f.close()
        assertTrue(q.shut)
        f.onFrame(20, 1f, 3, camera)
        assertTrue(q.tasks.isEmpty())
    }

    @Test
    fun `no searchable set is sent before any page exists`() {
        val sink = Sink(); val q = Queue()
        val f = feeder(sink, q)
        f.onFrame(0, 1f, 0, camera); q.drain()
        assertTrue(sink.added.isEmpty())
        assertEquals(emptySet<String>(), sink.searched)
        assertNull(sink.added.firstOrNull())
    }
}
