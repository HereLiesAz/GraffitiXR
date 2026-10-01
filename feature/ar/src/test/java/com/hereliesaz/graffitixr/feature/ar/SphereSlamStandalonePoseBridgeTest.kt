package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertEquals
import org.junit.Test

class SphereSlamStandalonePoseBridgeTest {

    @Test
    fun imuRotation_rotatesTranslationWithViewRotation() {
        val view = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            1f, 0f, -2f, 1f,
        )
        val delta = floatArrayOf(
            0f, -1f, 0f,
            1f, 0f, 0f,
            0f, 0f, 1f,
        )

        val out = rotateViewPoseKeepingCameraCenter(view, delta)

        assertEquals(0f, out[12], 0.0001f)
        assertEquals(1f, out[13], 0.0001f)
        assertEquals(-2f, out[14], 0.0001f)
        assertEquals(0f, out[0], 0.0001f)
        assertEquals(1f, out[1], 0.0001f)
        assertEquals(-1f, out[4], 0.0001f)
        assertEquals(0f, out[5], 0.0001f)
    }
}
