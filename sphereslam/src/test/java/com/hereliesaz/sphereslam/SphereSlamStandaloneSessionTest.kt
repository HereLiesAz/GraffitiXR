package com.hereliesaz.sphereslam

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SphereSlamStandaloneSessionTest {

    @Test
    fun normalizedReference_producesCenteredStandalonePoseWithoutClaimingPhysicalScale() {
        val engine = FakeEngine()
        val session = SphereSlamStandaloneSession(
            frameWidth = 4,
            frameHeight = 2,
            calibration = SphereSlamCalibration(4f, 4f, 2f, 1f),
            engineFactory = SphereSlamStandaloneSession.EngineFactory { _, _, _ -> engine },
        )

        val reference = session.addReference(
            luma = ByteBuffer.allocateDirect(8),
            width = 4,
            height = 2,
            referenceWidthMeters = 1f,
            physicallyMetric = false,
        )

        assertEquals(1f, reference.geometry.widthMeters, 0.0001f)
        assertEquals(0.5f, reference.geometry.heightMeters, 0.0001f)
        assertFalse(reference.physicallyMetric)
        assertEquals(64, reference.featureCount)

        engine.nextMatch = PlanarMatch(
            pageNo = 0,
            cameraFromPage3x4 = floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 1000f,
            ),
            reprojectionError = 0.3f,
            inlierCount = 19,
        )

        val pose = session.match(ByteBuffer.allocateDirect(8), timestampNs = 77L)
        assertNotNull(pose)
        assertEquals(77L, pose!!.timestampNs)
        assertEquals(19, pose.inlierCount)
        assertFalse(pose.physicallyMetric)
        // Centering shifts the KPM lower-left page origin to the middle of our 1 x 0.5 unit quad.
        assertEquals(0.5f, pose.viewMatrix[12], 0.0001f)
        assertEquals(-0.25f, pose.viewMatrix[13], 0.0001f)
        assertEquals(-1f, pose.viewMatrix[14], 0.0001f)

        session.close()
        assertEquals(1, engine.closeCalls)
    }

    @Test
    fun reset_dropsOldPageGeometryAndCreatesFreshEngine() {
        val engines = ArrayDeque(listOf(FakeEngine(), FakeEngine()))
        val session = SphereSlamStandaloneSession(
            4, 4,
            SphereSlamCalibration(4f, 4f, 2f, 2f),
            SphereSlamStandaloneSession.EngineFactory { _, _, _ -> engines.removeFirst() },
        )
        session.addReference(ByteBuffer.allocateDirect(16), 4, 4, 1f, false)
        assertTrue(session.hasReference)

        session.reset()

        assertFalse(session.hasReference)
        assertNull(session.match(ByteBuffer.allocateDirect(16), 1L))
        session.close()
    }

    private class FakeEngine : SphereSlamEngine {
        override val frameWidth: Int = 4
        override val frameHeight: Int = 2
        override val calibration = SphereSlamCalibration(4f, 4f, 2f, 1f)
        override var isReady: Boolean = true
            private set

        var nextMatch: PlanarMatch? = null
        var closeCalls = 0

        override fun addPage(
            luma: ByteBuffer,
            width: Int,
            height: Int,
            page: PlanarPage,
        ): Int = 64

        override fun match(luma: ByteBuffer): PlanarMatch? = nextMatch

        override fun close() {
            if (!isReady) return
            isReady = false
            closeCalls++
        }
    }
}
