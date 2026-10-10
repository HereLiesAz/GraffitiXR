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
    fun filledLandscapeFrameScalesGesturePixelsByTheCoverScale() {
        val result = standaloneScreenUnitsPerPixel(
            frameUnitsPerPixel = 0.001f,
            frameHeightPixels = 1080,
            frameAspect = 16f / 9f,
            surfaceWidthPixels = 1080,
            surfaceHeightPixels = 1920,
        )
        // FILL_CENTER covers 1080x1920 with a 1920x1080 frame at max(1080/1920, 1920/1080) =
        // 16/9 screen px per frame px, so each screen pixel spans 1080/1920 frame pixels.
        assertEquals(0.001f * 1080f / 1920f, result, 0.000001f)
    }

    @Test
    fun filledPortraitFrameOnATallScreenUsesTheBindingHeight() {
        val result = standaloneScreenUnitsPerPixel(
            frameUnitsPerPixel = 0.002f,
            frameHeightPixels = 640,
            frameAspect = 480f / 640f,
            surfaceWidthPixels = 1080,
            surfaceHeightPixels = 2400,
        )
        // Cover scale max(1080/480 = 2.25, 2400/640 = 3.75) = 3.75 (the sides are cropped).
        assertEquals(0.002f / 3.75f, result, 0.000001f)
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
        assertEquals(
            0f,
            standaloneScreenUnitsPerPixel(0.001f, 1080, Float.NaN, 1080, 1920),
            0f,
        )
    }
}
