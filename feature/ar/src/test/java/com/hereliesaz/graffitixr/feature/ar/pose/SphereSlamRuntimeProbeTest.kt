package com.hereliesaz.graffitixr.feature.ar.pose

import com.hereliesaz.sphereslam.PlanarMatch
import com.hereliesaz.sphereslam.PlanarPage
import com.hereliesaz.sphereslam.SphereSlamCalibration
import com.hereliesaz.sphereslam.SphereSlamEngine
import java.nio.ByteBuffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SphereSlamRuntimeProbeTest {

    @Test
    fun `unavailable native runtime fails closed without creating an engine`() {
        var createCalled = false

        val result = SphereSlamRuntimeProbe.probe(
            width = 640,
            height = 480,
            available = { false },
            create = { _, _, _ ->
                createCalled = true
                FakeEngine(ready = true)
            },
        )

        assertFalse(result)
        assertFalse(createCalled)
    }

    @Test
    fun `ready calibrated engine passes and is always closed`() {
        val engine = FakeEngine(ready = true)

        val result = SphereSlamRuntimeProbe.probe(
            width = 640,
            height = 480,
            available = { true },
            create = { width, height, calibration ->
                assertTrue(width == 640)
                assertTrue(height == 480)
                assertTrue(calibration.fx > 0f)
                assertTrue(calibration.fy > 0f)
                engine
            },
        )

        assertTrue(result)
        assertTrue(engine.closed)
    }

    @Test
    fun `zero-handle engine fails closed and is still closed`() {
        val engine = FakeEngine(ready = false)

        val result = SphereSlamRuntimeProbe.probe(
            width = 640,
            height = 480,
            available = { true },
            create = { _, _, _ -> engine },
        )

        assertFalse(result)
        assertTrue(engine.closed)
    }

    private class FakeEngine(
        private val ready: Boolean,
    ) : SphereSlamEngine {
        override val frameWidth: Int = 640
        override val frameHeight: Int = 480
        override val calibration = SphereSlamCalibration(640f, 640f, 320f, 240f)
        override val isReady: Boolean
            get() = ready && !closed

        var closed: Boolean = false
            private set

        override fun addPage(
            luma: ByteBuffer,
            width: Int,
            height: Int,
            page: PlanarPage,
        ): Int = error("not used by runtime probe")

        override fun match(luma: ByteBuffer): PlanarMatch? =
            error("not used by runtime probe")

        override fun close() {
            closed = true
        }
    }
}
