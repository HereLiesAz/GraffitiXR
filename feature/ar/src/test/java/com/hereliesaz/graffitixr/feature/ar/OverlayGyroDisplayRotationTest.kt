package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayGyroDisplayRotationTest {
    // Surface.ROTATION_* are compile-time constants (0..3), so no Android runtime is needed.
    @Test fun `surface rotations map to clockwise degrees`() {
        assertEquals(0, surfaceRotationToDegrees(0))
        assertEquals(90, surfaceRotationToDegrees(1))
        assertEquals(180, surfaceRotationToDegrees(2))
        assertEquals(270, surfaceRotationToDegrees(3))
    }

    @Test fun `the two landscapes stay distinct`() {
        // The 90<->270 flip is what a configuration-keyed cache missed.
        assert(surfaceRotationToDegrees(1) != surfaceRotationToDegrees(3))
    }

    @Test fun `an unknown rotation falls back to portrait`() {
        assertEquals(0, surfaceRotationToDegrees(42))
    }
}
