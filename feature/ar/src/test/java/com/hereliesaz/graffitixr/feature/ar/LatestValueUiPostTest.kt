package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** [LatestValueUiPost]: one post outstanding at most, the newest value applied, none lost. */
class LatestValueUiPostTest {

    private val queue = ArrayDeque<() -> Unit>()
    private val applied = mutableListOf<Int>()
    private val ui = LatestValueUiPost<Int>(post = { queue.addLast(it) }) { applied += it }

    private fun runQueue() {
        while (queue.isNotEmpty()) queue.removeFirst()()
    }

    @Test
    fun `a burst while the UI is busy posts once and applies only the newest`() {
        repeat(50) { ui.offer(it) }
        assertEquals(1, queue.size)
        runQueue()
        assertEquals(listOf(49), applied)
    }

    @Test
    fun `a value offered after a drain is posted again`() {
        ui.offer(1)
        runQueue()
        ui.offer(2)
        assertEquals(1, queue.size)
        runQueue()
        assertEquals(listOf(1, 2), applied)
    }

    @Test
    fun `reset recovers from a post the host discarded`() {
        ui.offer(1)
        queue.clear() // a Handler purge
        ui.offer(2)
        assertEquals("stuck: the dropped drain still counts as outstanding", 0, queue.size)
        ui.reset()
        ui.offer(3)
        runQueue()
        assertEquals(listOf(3), applied)
    }

    @Test
    fun `concurrent offers never lose the last value`() {
        repeat(200) { round ->
            val uiThread = Executors.newSingleThreadExecutor()
            val seen = java.util.concurrent.atomic.AtomicInteger(-1)
            val post = LatestValueUiPost<Int>(post = { uiThread.execute(it) }) { seen.set(it) }
            val workers = Executors.newFixedThreadPool(2)
            val done = CountDownLatch(2)
            repeat(2) { w ->
                workers.execute {
                    for (i in 0 until 500) post.offer(w * 1000 + i)
                    done.countDown()
                }
            }
            done.await(5, TimeUnit.SECONDS)
            post.offer(Int.MAX_VALUE) // the final value, after every other offer
            uiThread.shutdown()
            uiThread.awaitTermination(5, TimeUnit.SECONDS)
            workers.shutdown()
            assertEquals("round $round", Int.MAX_VALUE, seen.get())
        }
    }
}
