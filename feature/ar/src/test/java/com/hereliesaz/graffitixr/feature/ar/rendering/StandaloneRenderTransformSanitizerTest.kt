package com.hereliesaz.graffitixr.feature.ar.rendering

import org.junit.Assert.assertEquals
import org.junit.Test

class StandaloneRenderTransformSanitizerTest {

    @Test
    fun validTransformPassesThrough() {
        val t = StandaloneRenderTransformSanitizer.sanitize(
            panX = 2f,
            panY = -3f,
            scale = 1.5f,
            rotationZDeg = 20f,
            rotationXDeg = -30f,
            rotationYDeg = 40f,
        )
        assertEquals(2f, t.panX, 0f)
        assertEquals(-3f, t.panY, 0f)
        assertEquals(1.5f, t.scale, 0f)
        assertEquals(20f, t.rotationZDeg, 0f)
        assertEquals(-30f, t.rotationXDeg, 0f)
        assertEquals(40f, t.rotationYDeg, 0f)
    }

    @Test
    fun nonFiniteValuesCannotReachGlMatrices() {
        val t = StandaloneRenderTransformSanitizer.sanitize(
            panX = Float.NaN,
            panY = Float.POSITIVE_INFINITY,
            scale = Float.NaN,
            rotationZDeg = Float.NEGATIVE_INFINITY,
            rotationXDeg = Float.NaN,
            rotationYDeg = Float.POSITIVE_INFINITY,
        )
        assertEquals(0f, t.panX, 0f)
        assertEquals(0f, t.panY, 0f)
        assertEquals(1f, t.scale, 0f)
        assertEquals(0f, t.rotationZDeg, 0f)
        assertEquals(0f, t.rotationXDeg, 0f)
        assertEquals(0f, t.rotationYDeg, 0f)
    }

    @Test
    fun extremeFiniteValuesPassUnboundedAndRotationsNormalize() {
        val t = StandaloneRenderTransformSanitizer.sanitize(
            panX = Float.MAX_VALUE,
            panY = -Float.MAX_VALUE,
            scale = 1_000_000f,
            rotationZDeg = 740f,
            rotationXDeg = -740f,
            rotationYDeg = 540f,
        )
        assertEquals(Float.MAX_VALUE, t.panX, 0f)
        assertEquals(-Float.MAX_VALUE, t.panY, 0f)
        assertEquals(1_000_000f, t.scale, 0f)
        assertEquals(20f, t.rotationZDeg, 0f)
        assertEquals(-20f, t.rotationXDeg, 0f)
        assertEquals(180f, t.rotationYDeg, 0f)
    }

    @Test
    fun scaleHasNoBoundsOnlyUnusableValuesAreReplaced() {
        assertEquals(1f, StandaloneRenderTransformSanitizer.sanitize(0f, 0f, 0f, 0f, 0f, 0f).scale, 0f)
        assertEquals(1f, StandaloneRenderTransformSanitizer.sanitize(0f, 0f, -2f, 0f, 0f, 0f).scale, 0f)
        assertEquals(0.001f, StandaloneRenderTransformSanitizer.sanitize(0f, 0f, 0.001f, 0f, 0f, 0f).scale, 0f)
        assertEquals(100f, StandaloneRenderTransformSanitizer.sanitize(0f, 0f, 100f, 0f, 0f, 0f).scale, 0f)
    }
}
