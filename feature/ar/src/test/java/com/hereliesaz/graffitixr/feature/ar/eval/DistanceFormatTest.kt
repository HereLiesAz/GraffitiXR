package com.hereliesaz.graffitixr.feature.ar.eval

import org.junit.Assert.assertEquals
import org.junit.Test

class DistanceFormatTest {
    @Test fun `metric`() {
        assertEquals("2.3 m", DistanceFormat.format(2.34f, imperial = false))
    }

    @Test fun `imperial converts meters to feet`() {
        // 2.286 m = 7.50 ft
        assertEquals("7.5 ft", DistanceFormat.format(2.286f, imperial = true))
    }

    @Test fun `invalid is dash`() {
        assertEquals("—", DistanceFormat.format(0f, false))
        assertEquals("—", DistanceFormat.format(-1f, true))
    }
}
