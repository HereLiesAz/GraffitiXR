package com.hereliesaz.graffitixr.feature.ar

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandaloneDepthSchedulingTest {

    /** Runs nothing until [runAll]; lets a test hold a job "in flight". */
    private class ManualExecutor : Executor {
        val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            pending.addLast(command)
        }
        fun runAll() {
            while (pending.isNotEmpty()) pending.removeFirst().run()
        }
    }

    // ---- cadence -------------------------------------------------------------------------------

    @Test
    fun `first request is due immediately`() {
        assertTrue(StandaloneDepthCadence().isDue(nowMs = 0L))
    }

    @Test
    fun `static view refreshes only at the max interval`() {
        val c = StandaloneDepthCadence(minIntervalMs = 1_000L, maxIntervalMs = 2_000L)
        c.onViewDirection(10f, 0f)
        c.markRun(0L)
        c.onViewDirection(12f, 1f) // same 30-degree tile
        assertFalse(c.isDue(500L))
        assertFalse(c.isDue(1_500L))
        assertFalse(c.isDue(1_999L))
        assertTrue(c.isDue(2_000L))
    }

    @Test
    fun `heading noise over a floor is not a tile change`() {
        val c = StandaloneDepthCadence(minIntervalMs = 1_000L, maxIntervalMs = 2_000L)
        c.onViewDirection(10f, -89f)
        c.markRun(0L)
        // Looking straight down, the heading swings wildly with sub-degree tilt.
        for (h in listOf(95f, 200f, 310f)) {
            c.onViewDirection(h, -88.5f)
            assertFalse(c.isDue(1_500L))
        }
        assertTrue(c.isDue(2_000L)) // still refreshed periodically
        // Leaving the cap for the wall is a real change.
        c.onViewDirection(200f, -30f)
        assertTrue(c.isDue(1_000L))
    }

    @Test
    fun `stress scale stretches both intervals`() {
        val c = StandaloneDepthCadence(minIntervalMs = 1_000L, maxIntervalMs = 2_000L)
        c.onViewDirection(10f, 0f)
        c.markRun(0L)
        assertFalse(c.isDue(2_000L, intervalScale = 4))
        assertTrue(c.isDue(8_000L, intervalScale = 4))
        c.onViewDirection(50f, 0f) // next heading tile
        assertFalse(c.isDue(1_000L, intervalScale = 4))
        assertTrue(c.isDue(4_000L, intervalScale = 4))
    }

    @Test
    fun `tile change is due after the min interval but not before`() {
        val c = StandaloneDepthCadence(minIntervalMs = 1_000L, maxIntervalMs = 2_000L)
        c.onViewDirection(10f, 0f)
        c.markRun(0L)
        c.onViewDirection(50f, 0f) // next heading tile
        assertFalse(c.isDue(999L))
        assertTrue(c.isDue(1_000L))
        c.markRun(1_000L)
        // The run consumed the change: nothing new, so wait for the max interval.
        assertFalse(c.isDue(2_500L))
        assertTrue(c.isDue(3_000L))
    }

    @Test
    fun `elevation tile change and heading wrap are handled`() {
        val c = StandaloneDepthCadence(minIntervalMs = 0L, maxIntervalMs = 10_000L)
        c.onViewDirection(359f, 0f)
        c.markRun(0L)
        c.onViewDirection(-1f, 0f) // same as 359
        assertFalse(c.isDue(1L))
        c.onViewDirection(359f, 45f)
        assertTrue(c.isDue(1L))
    }

    @Test
    fun `atlas growth triggers a run once`() {
        val c = StandaloneDepthCadence(minIntervalMs = 1_000L, maxIntervalMs = 5_000L)
        c.markRun(0L)
        c.onAtlasGrowth()
        assertFalse(c.isDue(500L))
        assertTrue(c.isDue(1_000L))
        c.markRun(1_000L)
        assertFalse(c.isDue(2_500L))
    }

    @Test
    fun `non-finite directions are ignored and reset re-arms`() {
        val c = StandaloneDepthCadence(minIntervalMs = 0L, maxIntervalMs = 10_000L)
        c.markRun(0L)
        c.onViewDirection(Float.NaN, 0f)
        assertFalse(c.isDue(1L))
        c.reset()
        assertTrue(c.isDue(1L))
    }

    // ---- drop-if-busy worker -------------------------------------------------------------------

    @Test
    fun `a second submission while one is in flight is dropped`() {
        val exec = ManualExecutor()
        val worker = DropIfBusyWorker<String>(exec)
        var runs = 0
        assertTrue(worker.trySubmit({ runs++; "a" }))
        assertTrue(worker.isBusy)
        assertFalse(worker.trySubmit({ runs++; "b" }))
        assertEquals(1, exec.pending.size)
        exec.runAll()
        assertEquals(1, runs)
        assertFalse(worker.isBusy)
        assertEquals("a", worker.takeResult())
        assertNull("result is consumed once", worker.takeResult())
        // Free again once the job finished.
        assertTrue(worker.trySubmit({ "c" }))
    }

    @Test
    fun `latest finished result wins and null means nothing to apply`() {
        val exec = ManualExecutor()
        val worker = DropIfBusyWorker<String>(exec)
        worker.trySubmit({ "first" })
        exec.runAll()
        worker.trySubmit({ "second" })
        exec.runAll()
        assertEquals("second", worker.takeResult())
        worker.trySubmit({ null })
        exec.runAll()
        assertNull(worker.takeResult())
    }

    @Test
    fun `an exception releases the busy flag and reports`() {
        val exec = ManualExecutor()
        val worker = DropIfBusyWorker<String>(exec)
        var reported: Throwable? = null
        worker.trySubmit({ error("boom") }, onError = { reported = it })
        exec.runAll()
        assertNotNull(reported)
        assertFalse(worker.isBusy)
        assertNull(worker.takeResult())
    }

    @Test
    fun `results finishing after close are discarded and close runs once`() {
        val exec = ManualExecutor()
        var closes = 0
        val worker = DropIfBusyWorker<String>(exec, onClose = { closes++ })
        worker.trySubmit({ "late" })
        worker.close()
        worker.close()
        exec.runAll()
        assertEquals(1, closes)
        assertNull(worker.takeResult())
        assertFalse(worker.trySubmit({ "after" }))
    }

    @Test
    fun `a rejecting executor does not wedge the worker busy`() {
        val worker = DropIfBusyWorker<String>({ throw RejectedExecutionException() })
        assertFalse(worker.trySubmit({ "x" }))
        assertFalse(worker.isBusy)
    }

    // ---- luma downsample -----------------------------------------------------------------------

    @Test
    fun `downsample keeps the short side at least the minimum`() {
        assertEquals(4, DepthLumaDownsampler.stride(1080, 1440))
        assertEquals(1, DepthLumaDownsampler.stride(480, 640))
        val grid = DepthLumaDownsampler.downsample(ByteArray(1080 * 1440), 1080, 1440)!!
        assertEquals(270, grid.width)
        assertEquals(360, grid.height)
        assertTrue(minOf(grid.width, grid.height) >= DepthLumaDownsampler.DEFAULT_MIN_SHORT_SIDE)
    }

    @Test
    fun `downsample box-averages unsigned luma`() {
        // 4x2 frame; minShortSide 1 gives stride min(4, 2) / 1 = 2, i.e. two 2x2 blocks.
        val luma = byteArrayOf(
            0, 10, 200.toByte(), 250.toByte(),
            20, 30, 240.toByte(), 254.toByte(),
        )
        val grid = DepthLumaDownsampler.downsample(luma, 4, 2, minShortSide = 1)!!
        assertEquals(2, grid.width)
        assertEquals(1, grid.height)
        assertArrayEquals(
            byteArrayOf(15, ((200 + 250 + 240 + 254) / 4).toByte()),
            grid.bytes,
        )
    }

    @Test
    fun `downsample copies small frames and rejects short buffers`() {
        val luma = ByteArray(16) { it.toByte() }
        val grid = DepthLumaDownsampler.downsample(luma, 4, 4)!!
        assertEquals(4, grid.width)
        assertArrayEquals(luma, grid.bytes)
        assertTrue("a copy, not the frame buffer", grid.bytes !== luma)
        assertNull(DepthLumaDownsampler.downsample(ByteArray(3), 2, 2))
        assertNull(DepthLumaDownsampler.downsample(ByteArray(0), 0, 2))
    }

    // ---- load guard ----------------------------------------------------------------------------

    @Test
    fun `load guard allows the first attempt and backs off after a failure`() {
        val g = DepthLoadGuard(backoffMs = 1_000L, maxBackoffMs = 8_000L, maxFailures = 4)
        assertTrue(g.shouldAttempt(0L))
        g.onFailure(0L)
        assertFalse(g.shouldAttempt(999L))
        assertTrue(g.shouldAttempt(1_000L))
        g.onFailure(1_000L) // second failure: 2 s
        assertFalse(g.shouldAttempt(2_999L))
        assertTrue(g.shouldAttempt(3_000L))
    }

    @Test
    fun `load guard fails for good after the failure budget`() {
        val g = DepthLoadGuard(backoffMs = 10L, maxBackoffMs = 10L, maxFailures = 2)
        g.onFailure(0L)
        assertFalse(g.failedForGood())
        g.onFailure(100L)
        assertTrue(g.failedForGood())
        assertFalse("never retried once failed for good", g.shouldAttempt(Long.MAX_VALUE / 2))
    }

    @Test
    fun `load guard backoff is capped and a success clears the history`() {
        val g = DepthLoadGuard(backoffMs = 1_000L, maxBackoffMs = 3_000L, maxFailures = 10)
        repeat(5) { g.onFailure(0L) }
        assertFalse(g.shouldAttempt(2_999L))
        assertTrue(g.shouldAttempt(3_000L))
        g.onSuccess()
        assertTrue(g.shouldAttempt(0L))
        assertFalse(g.failedForGood())
    }
}
