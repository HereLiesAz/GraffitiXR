package com.hereliesaz.graffitixr.feature.ar.eval

import org.junit.Assert.assertEquals
import org.junit.Test

class EvalMetricsTest {
    // Build matrices as plain FloatArrays so the tests stay pure-JVM (no android.opengl.Matrix,
    // which is a stubbed "not mocked" class outside Robolectric). Column-major identity.
    private fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    @Test
    fun `poseError is zero for identical poses`() {
        val e = EvalMetrics.poseError(identity(), identity())
        assertEquals(0f, e.translationMm, 1e-3f)
        assertEquals(0f, e.rotationDeg, 1e-3f)
    }

    @Test
    fun `poseError reports translation in millimeters`() {
        val truth = identity()
        val candidate = identity().also { it[12] = 0.10f } // +0.10 m on X
        val e = EvalMetrics.poseError(candidate, truth)
        assertEquals(100f, e.translationMm, 1e-2f) // 0.10 m = 100 mm
        assertEquals(0f, e.rotationDeg, 1e-3f)
    }

    @Test
    fun `jitter is zero for a stationary point`() {
        val pts = List(10) { floatArrayOf(1f, 2f, 3f) }
        assertEquals(0f, EvalMetrics.jitterMm(pts), 1e-3f)
    }

    @Test
    fun `jitter is stddev of distance from centroid in mm`() {
        // Two points: (0,0,0) and (0,0,0.02) -> centroid z=0.01, each 0.01 m = 10 mm away. stddev = 10mm.
        val pts = listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0.02f))
        assertEquals(10f, EvalMetrics.jitterMm(pts), 1e-2f)
    }

    @Test
    fun `availability is usable over total`() {
        assertEquals(0.75f, EvalMetrics.availability(usable = 3, total = 4), 1e-4f)
        assertEquals(0f, EvalMetrics.availability(usable = 0, total = 0), 1e-4f) // guard /0
    }

    @Test
    fun `recoveryMs is relock minus loss, null if never relocked`() {
        assertEquals(1500L, EvalMetrics.recoveryMs(lossMs = 1000L, relockMs = 2500L))
        assertEquals(null, EvalMetrics.recoveryMs(lossMs = 1000L, relockMs = null))
    }
}
