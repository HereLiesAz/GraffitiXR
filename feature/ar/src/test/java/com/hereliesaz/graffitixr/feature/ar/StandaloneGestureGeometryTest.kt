package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertEquals
import org.junit.Test

class StandaloneGestureGeometryTest {

    @Test
    fun matchingFrameAndSurfaceAspectKeepsPixelScale() {
        val result = standaloneScreenUnitsPerPixel(
            frameUnitsPerPixel = 0.002f,
            frameHeightPixels = 1920,
            frameAspect = 1080f / 1920f,
            surfaceWidthPixels = 1080,
            surfaceHeightPixels = 1920,
        )
        assertEquals(0.002f, result, 0.000001f)
    }

    @Test
    fun letterboxedLandscapeFrameScalesGesturePixelsToViewport() {
        val result = standaloneScreenUnitsPerPixel(
            frameUnitsPerPixel = 0.001f,
            frameHeightPixels = 1080,
            frameAspect = 16f / 9f,
            surfaceWidthPixels = 1080,
            surfaceHeightPixels = 1920,
        )
        // FIT_CENTER viewport height is 607px, so each screen pixel spans ~1080/607 frame pixels.
        assertEquals(0.001f * 1080f / 607f, result, 0.000001f)
    }

    @Test
    fun invalidGeometryDoesNotPublishBogusGestureScale() {
        assertEquals(
            0f,
            standaloneScreenUnitsPerPixel(0.001f, 1080, 16f / 9f, 0, 1920),
            0f,
        )
        assertEquals(
            0f,
            standaloneScreenUnitsPerPixel(Float.NaN, 1080, 16f / 9f, 1080, 1920),
            0f,
        )
    }
}
