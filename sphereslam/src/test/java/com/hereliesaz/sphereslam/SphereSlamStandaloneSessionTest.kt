package com.hereliesaz.sphereslam

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
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
    fun secondPageMatch_isRebasedIntoCanonicalWallFrame() {
        val engine = FakeEngine()
        val session = SphereSlamStandaloneSession(
            frameWidth = 4,
            frameHeight = 2,
            calibration = SphereSlamCalibration(4f, 4f, 2f, 1f),
            engineFactory = SphereSlamStandaloneSession.EngineFactory { _, _, _ -> engine },
        )
        session.addReference(
            ByteBuffer.allocateDirect(8),
            4,
            2,
            1f,
            true,
            pageNo = 0,
        )
        session.addReference(
            ByteBuffer.allocateDirect(8),
            4,
            2,
            1f,
            true,
            pageNo = 1,
            canonicalFromPage = SphereSlamPoseMath.identity4().also {
                // Page 1 is one metre to the right of canonical page 0.
                it[12] = 1f
            },
        )
        engine.nextMatch = PlanarMatch(
            pageNo = 1,
            cameraFromPage3x4 = floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 1000f,
            ),
            reprojectionError = 0.2f,
            inlierCount = 24,
        )

        val pose = requireNotNull(session.match(ByteBuffer.allocateDirect(8), 99L))

        assertEquals(1, pose.pageNo)
        // The page-local camera is centered over page 1. In canonical coordinates that page sits at
        // +1m X, so camera-from-canonical carries -1m X while retaining the page-centering Y/Z.
        assertEquals(-0.5f, pose.viewMatrix[12], 0.0001f)
        assertEquals(-0.25f, pose.viewMatrix[13], 0.0001f)
        assertEquals(-1f, pose.viewMatrix[14], 0.0001f)
        session.close()
    }

    @Test
    fun switchingAcrossThreePages_keepsExactlyOneCanonicalCameraPose() {
        val engine = FakeEngine()
        val session = SphereSlamStandaloneSession(
            frameWidth = 4,
            frameHeight = 2,
            calibration = SphereSlamCalibration(4f, 4f, 2f, 1f),
            engineFactory = SphereSlamStandaloneSession.EngineFactory { _, _, _ -> engine },
        )
        session.addReference(ByteBuffer.allocateDirect(8), 4, 2, 1f, true, pageNo = 0)
        session.addReference(
            ByteBuffer.allocateDirect(8),
            4,
            2,
            1f,
            true,
            pageNo = 1,
            canonicalFromPage = SphereSlamPoseMath.identity4().also { it[12] = 1f },
        )
        session.addReference(
            ByteBuffer.allocateDirect(8),
            4,
            2,
            1f,
            true,
            pageNo = 2,
            canonicalFromPage = SphereSlamPoseMath.identity4().also { it[12] = 2f },
        )

        fun match(pageNo: Int, pageLocalTxMm: Float): FloatArray {
            engine.nextMatch = PlanarMatch(
                pageNo = pageNo,
                cameraFromPage3x4 = floatArrayOf(
                    1f, 0f, 0f, pageLocalTxMm,
                    0f, 1f, 0f, 0f,
                    0f, 0f, 1f, 1000f,
                ),
                reprojectionError = 0.2f,
                inlierCount = 24,
            )
            return requireNotNull(
                session.match(ByteBuffer.allocateDirect(8), pageNo.toLong()),
            ).viewMatrix
        }

        // Same physical camera position, described in each page's local KPM coordinates.
        val fromRoot = match(pageNo = 0, pageLocalTxMm = -2000f)
        val fromPage1 = match(pageNo = 1, pageLocalTxMm = -1000f)
        val fromPage2 = match(pageNo = 2, pageLocalTxMm = 0f)

        assertArrayEquals(fromRoot, fromPage1, 0.0001f)
        assertArrayEquals(fromRoot, fromPage2, 0.0001f)
        assertEquals(-1.5f, fromRoot[12], 0.0001f)
        assertEquals(-0.25f, fromRoot[13], 0.0001f)
        assertEquals(-1f, fromRoot[14], 0.0001f)

        session.close()
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
