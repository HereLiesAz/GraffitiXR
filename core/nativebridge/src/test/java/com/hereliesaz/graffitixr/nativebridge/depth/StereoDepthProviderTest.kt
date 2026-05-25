package com.hereliesaz.graffitixr.nativebridge.depth

import com.hereliesaz.graffitixr.nativebridge.SlamManager
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class StereoDepthProviderTest {

    private val slamManager: SlamManager = mockk(relaxed = true)
    private lateinit var provider: StereoDepthProvider

    @Before
    fun setup() {
        provider = StereoDepthProvider(slamManager)
    }

    @Test
    fun `processStereoFrames calls slamManager`() {
        val left = ByteArray(10)
        val right = ByteArray(10)
        val w = 640
        val h = 480
        val ts = 12345L

        provider.processStereoFrames(left, right, w, h, ts)

        verify { slamManager.feedStereoData(any(), any(), w, h, ts) }
    }

    @Test
    fun `processStereoFrames does NOT call slamManager when data is empty`() {
        val left = ByteArray(0)
        val right = ByteArray(0)
        val w = 640
        val h = 480
        val ts = 12345L

        provider.processStereoFrames(left, right, w, h, ts)

        verify(exactly = 0) { slamManager.feedStereoData(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `submitFrame skips first frame then feeds the temporal stereo pair`() {
        val w = 8
        val h = 8
        fun frame() = ByteBuffer.allocateDirect(w * h).order(ByteOrder.nativeOrder())

        // First frame establishes the buffers and is intentionally not fed.
        provider.submitFrame(frame(), w, h, 1L)
        verify(exactly = 0) { slamManager.feedStereoData(any(), any(), any(), any(), any()) }

        // Second frame pairs with the previous one and is fed.
        provider.submitFrame(frame(), w, h, 2L)
        verify { slamManager.feedStereoData(any(), any(), w, h, 2L) }
    }

    @Test
    fun `concurrent submitFrame and processStereoFrames do not throw`() {
        val threads = 8
        val iterations = 200
        val w = 16
        val h = 16
        val size = w * h
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val failure = AtomicReference<Throwable?>(null)

        repeat(threads) { t ->
            pool.execute {
                try {
                    start.await()
                    repeat(iterations) { i ->
                        if (t % 2 == 0) {
                            provider.submitFrame(
                                ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()),
                                w, h, (t * iterations + i).toLong()
                            )
                        } else {
                            provider.processStereoFrames(ByteArray(size), ByteArray(size), w, h, i.toLong())
                        }
                    }
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()
        assertTrue("threads did not finish", done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertTrue("concurrent access threw: ${failure.get()}", failure.get() == null)
    }
}
