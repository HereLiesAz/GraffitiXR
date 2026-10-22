package com.hereliesaz.graffitixr.feature.ar.rendering

import org.junit.Assert.assertEquals
import org.junit.Test

class FallbackPlacementTest {
    @Test
    fun `a camera facing a wall places the fallback two metres ahead`() {
        assertEquals(2.0f, fallbackPlacementDistance(forwardY = 0f), 0f)
    }

    @Test
    fun `a camera looking straight down at a canvas places it at arm's length`() {
        assertEquals(0.35f, fallbackPlacementDistance(forwardY = -1f), 0f)
    }

    @Test
    fun `a gentle downward tilt still counts as a wall`() {
        // ~30° below horizontal: forward y = -sin(30°) = -0.5.
        assertEquals(2.0f, fallbackPlacementDistance(forwardY = -0.5f), 0f)
    }
}
