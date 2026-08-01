package com.hereliesaz.graffitixr.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraTargetFpsTest {

    @Test
    fun `the cycle visits all four choices and wraps`() {
        var v = CameraTargetFps.FPS_30
        val seen = mutableListOf(v)
        repeat(3) { v = CameraTargetFps.next(v); seen.add(v) }
        assertEquals(
            listOf(
                CameraTargetFps.FPS_30,
                CameraTargetFps.FPS_60,
                CameraTargetFps.DEVICE_DEFAULT,
                CameraTargetFps.DEVICE_MAX,
            ),
            seen,
        )
        assertEquals("must wrap", CameraTargetFps.FPS_30, CameraTargetFps.next(v))
    }

    /**
     * The sentinels must not collide with any real frame rate, or a stored preference would change
     * meaning. `0` and `-1` are chosen because no camera reports them.
     */
    @Test
    fun `sentinels cannot be mistaken for frame rates`() {
        assertTrue(CameraTargetFps.DEVICE_DEFAULT <= 0)
        assertTrue(CameraTargetFps.DEVICE_MAX < 0)
        assertTrue(CameraTargetFps.FPS_30 > 0 && CameraTargetFps.FPS_60 > 0)
    }

    /**
     * 30 and 60 keep their literal values, which is what lets every preference saved before the
     * device-resolved options existed keep meaning exactly what it meant. A migration here would be
     * silent and untestable, so the encoding avoids needing one.
     */
    @Test
    fun `the pre-existing values are unchanged`() {
        assertEquals(30, CameraTargetFps.FPS_30)
        assertEquals(60, CameraTargetFps.FPS_60)
    }

    @Test
    fun `labels name the sentinels and print real rates`() {
        assertEquals("30", CameraTargetFps.label(CameraTargetFps.FPS_30))
        assertEquals("60", CameraTargetFps.label(CameraTargetFps.FPS_60))
        assertEquals("Device default", CameraTargetFps.label(CameraTargetFps.DEVICE_DEFAULT))
        assertEquals("Device max", CameraTargetFps.label(CameraTargetFps.DEVICE_MAX))
    }

    /** A value from a future build (or a corrupt preference) must not strand the cycle. */
    @Test
    fun `an unknown stored value re-enters the cycle`() {
        assertEquals(CameraTargetFps.FPS_30, CameraTargetFps.next(120))
        assertEquals(CameraTargetFps.FPS_30, CameraTargetFps.next(-99))
    }
}
