package com.hereliesaz.sphereslam

import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SphereSlamTrackerTest {
    @Test
    fun `packLuma removes row padding without moving source position`() {
        val source = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 99, 99, 4, 5, 6, 99, 99))
        val packed = SphereSlamTracker.packLuma(source, width = 3, height = 2, rowStride = 5)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), packed)
        assertEquals(0, source.position())
    }

    @Test
    fun `matching is asynchronous and publishes sidecar observation`() {
        val matched = CountDownLatch(1)
        val fake = object : SphereSlamTracker.Native {
            override val available = true
            override fun create(camera: SphereSlamTracker.CameraModel) = 7L
            override fun destroy(handle: Long) = Unit
            override fun setReference(
                handle: Long, luma: ByteArray, width: Int, height: Int, dpi: Float,
                pageNo: Int, imageNo: Int, maxFeatures: Int,
            ) = true
            override fun match(
                handle: Long, luma: ByteArray, timestampNs: Long,
            ): SphereSlamTracker.Observation {
                matched.countDown()
                return SphereSlamTracker.Observation(
                    timestampNs, 4, 0.25f, 31, FloatArray(12) { it.toFloat() }
                )
            }
        }

        SphereSlamTracker(fake).use { tracker ->
            tracker.configure(SphereSlamTracker.CameraModel(2, 2, 500f, 500f, 1f, 1f))

            val readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!tracker.isReferenceReady && System.nanoTime() < readyDeadline) {
                tracker.setReference(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)), 2, 2, 2)
                Thread.sleep(5)
            }
            assertTrue(tracker.isReferenceReady)

            tracker.submitFrame(
                ByteBuffer.wrap(byteArrayOf(4, 3, 2, 1)), 2, 2, 2, timestampNs = 123L
            )
            assertTrue(matched.await(2, TimeUnit.SECONDS))

            val observationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var observation = tracker.latestObservation()
            while (observation == null && System.nanoTime() < observationDeadline) {
                Thread.sleep(5)
                observation = tracker.latestObservation()
            }

            assertNotNull(observation)
            assertEquals(123L, observation!!.timestampNs)
            assertEquals(4, observation.pageNo)
            assertEquals(31, observation.inliers)
        }
    }
    @Test
    fun `rectified reference dimensions are independent from live camera dimensions`() {
        val referenceAdded = CountDownLatch(1)
        val fake = object : SphereSlamTracker.Native {
            override val available = true
            override fun create(camera: SphereSlamTracker.CameraModel) = 9L
            override fun destroy(handle: Long) = Unit
            override fun setReference(
                handle: Long, luma: ByteArray, width: Int, height: Int, dpi: Float,
                pageNo: Int, imageNo: Int, maxFeatures: Int,
            ): Boolean {
                assertEquals(640, width)
                assertEquals(320, height)
                referenceAdded.countDown()
                return true
            }
            override fun match(
                handle: Long, luma: ByteArray, timestampNs: Long,
            ): SphereSlamTracker.Observation? = null
        }

        SphereSlamTracker(fake).use { tracker ->
            tracker.configure(SphereSlamTracker.CameraModel(1280, 720, 900f, 900f, 640f, 360f))
            val configureDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!tracker.isNativeAvailable || System.nanoTime() >= configureDeadline) {
                Thread.sleep(1)
            }
            tracker.setReference(
                ByteBuffer.wrap(ByteArray(640 * 320)),
                width = 640,
                height = 320,
                rowStride = 640,
                dpi = 80f,
            )
            assertTrue(referenceAdded.await(2, TimeUnit.SECONDS))
            val readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!tracker.isReferenceReady && System.nanoTime() < readyDeadline) Thread.sleep(5)
            assertTrue(tracker.isReferenceReady)
            assertEquals(0.2032f, tracker.currentReferenceGeometry()!!.widthMeters, 0.0001f)
        }
    }

}
