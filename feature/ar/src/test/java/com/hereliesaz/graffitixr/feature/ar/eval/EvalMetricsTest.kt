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
}
