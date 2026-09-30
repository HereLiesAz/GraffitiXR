package com.hereliesaz.sphereslam

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KpmSphereSlamEngineTest {
    private val calibration = SphereSlamCalibration(800f, 810f, 320f, 240f)

    @Test
    fun addPage_andMatch_useIndependentCalibratedNativeSession() {
        val api = FakeKpmApi()
        val engine = KpmSphereSlamEngine(4, 4, calibration, api)
        assertTrue(engine.isReady)
        assertEquals(calibration, api.createdWith)

        val reference = ByteBuffer.allocateDirect(16)
        val generated = engine.addPage(reference, 4, 4, PlanarPage(pageNo = 7))
        assertEquals(42, generated)

        val live = ByteBuffer.allocateDirect(16)
        val match = engine.match(live)
        assertNotNull(match)
        assertEquals(7, match!!.pageNo)
        assertEquals(9, match.inlierCount)
        assertEquals(0.25f, match.reprojectionError, 0f)
        assertEquals(12, match.cameraFromPage3x4.size)

        engine.close()
        assertFalse(engine.isReady)
        assertEquals(1, api.destroyCalls)
    }

    @Test
    fun noMatch_returnsNull_withoutAffectingSession() {
        val api = FakeKpmApi(matchPage = -1)
        val engine = KpmSphereSlamEngine(2, 2, calibration, api)

        assertNull(engine.match(ByteBuffer.allocateDirect(4)))
        assertTrue(engine.isReady)

        engine.close()
    }

    private class FakeKpmApi(
        private val matchPage: Int = 7,
    ) : KpmApi {
        var destroyCalls = 0
        var createdWith: SphereSlamCalibration? = null

        override fun create(
            width: Int,
            height: Int,
            calibration: SphereSlamCalibration,
        ): Long {
            createdWith = calibration
            return 123L
        }

        override fun addPage(
            session: Long,
            luma: ByteBuffer,
            width: Int,
            height: Int,
            referenceDpi: Float,
            pageNo: Int,
            imageNo: Int,
            maxFeatures: Int,
        ): Int = 42

        override fun match(session: Long, luma: ByteBuffer, out: FloatArray): Int {
            if (matchPage < 0) return -1
            for (i in 0 until 12) out[i] = i.toFloat()
            out[12] = 0.25f
            out[13] = 9f
            return matchPage
        }

        override fun destroy(session: Long) {
            destroyCalls++
        }
    }
}
