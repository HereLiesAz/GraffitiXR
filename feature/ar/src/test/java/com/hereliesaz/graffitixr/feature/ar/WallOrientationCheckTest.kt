package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.graffitixr.feature.ar.WallOrientationCheck.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class WallOrientationCheckTest {

    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    // ENU (x east, y north, z up) to a +Y-up world (x east, y up, z -north).
    private val enuToYUp = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, -1f, 0f)

    @Test fun `no saved normal passes`() {
        assertEquals(Verdict.UNKNOWN, WallOrientationCheck.check(null, floatArrayOf(0f, 0f, 1f), null))
    }

    @Test fun `a vertical wall matches a vertical wall without attitude`() {
        // Saved: faces north (ENU y). Solved: horizontal normal in world.
        assertEquals(Verdict.OK, WallOrientationCheck.check(floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f), null))
    }

    @Test fun `a floor standing in for a wall is a tilt mismatch`() {
        assertEquals(Verdict.TILT_MISMATCH, WallOrientationCheck.check(floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 1f, 0f), null))
    }

    @Test fun `with attitude the saved normal is carried into the world and the sign does not matter`() {
        // Saved faces north (ENU y) -> world -z. Solved faces +z (the same line, other way).
        assertEquals(Verdict.OK, WallOrientationCheck.check(floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f), enuToYUp))
    }

    @Test fun `with attitude a wall turned 90 degrees is a turn mismatch`() {
        // Saved faces north -> world -z; solved faces east (+x).
        assertEquals(Verdict.TURN_MISMATCH, WallOrientationCheck.check(floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 0f, 0f), enuToYUp))
    }

    @Test fun `worldFromEarth is the identity when camera and attitude agree`() {
        // GL camera rotation = identity; earthFromCv = diag(1,-1,-1) makes worldFromEarth = identity.
        val e = floatArrayOf(1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, -1f)
        assertArrayEquals(identity, WallOrientationCheck.worldFromEarth(identity, e), 1e-6f)
    }
}
