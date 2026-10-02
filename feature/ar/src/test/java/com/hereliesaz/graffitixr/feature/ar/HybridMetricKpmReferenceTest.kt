package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridMetricKpmReferenceTest {
    @Test
    fun `fronto-parallel wall derives a metric page with physical aspect`() {
        val geometry = HybridMetricKpmReference.deriveGeometry(
            imageWidth = 1000,
            imageHeight = 800,
            fx = 800f,
            fy = 800f,
            cx = 500f,
            cy = 400f,
            glView = identity(),
            wallPlane = floatArrayOf(
                0f, 0f, -2f,
                0f, 0f, 1f,
            ),
        )
        assertNotNull(geometry)
        geometry!!
        assertTrue(geometry.widthMeters > 1f)
        assertTrue(geometry.heightMeters > 0.7f)
        assertEquals(
            geometry.widthMeters / geometry.heightMeters,
            geometry.outputWidth.toFloat() / geometry.outputHeight.toFloat(),
            0.02f,
        )
        assertEquals(geometry.widthMeters, geometry.pageGeometry.widthMeters, 0.01f)
    }

    @Test
    fun `oblique wall still produces an in-frame metric rectangle`() {
        val angle = Math.toRadians(30.0)
        val normal = floatArrayOf(
            kotlin.math.sin(angle).toFloat(),
            0f,
            kotlin.math.cos(angle).toFloat(),
        )
        val geometry = HybridMetricKpmReference.deriveGeometry(
            imageWidth = 1280,
            imageHeight = 720,
            fx = 900f,
            fy = 900f,
            cx = 640f,
            cy = 360f,
            glView = identity(),
            wallPlane = floatArrayOf(
                0f, 0f, -2.2f,
                normal[0], normal[1], normal[2],
            ),
        )
        assertNotNull(geometry)
        geometry!!
        assertTrue(geometry.sourceCorners.all {
            it.x in 1f..1278f && it.y in 1f..718f
        })
        assertTrue(geometry.referenceDpi > 0f)
    }

    @Test
    fun `degenerate wall geometry is refused rather than assigned fake scale`() {
        val geometry = HybridMetricKpmReference.deriveGeometry(
            imageWidth = 1000,
            imageHeight = 800,
            fx = 800f,
            fy = 800f,
            cx = 500f,
            cy = 400f,
            glView = identity(),
            wallPlane = floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f),
        )
        assertEquals(null, geometry)
    }

    private fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )
}
