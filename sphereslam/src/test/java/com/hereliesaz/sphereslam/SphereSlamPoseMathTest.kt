package com.hereliesaz.sphereslam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SphereSlamPoseMathTest {

    @Test
    fun dpiForReferenceWidth_convertsPixelsAndMetres() {
        // 0.254 m == 10 inches; 1000 px / 10 in == 100 dpi.
        assertEquals(
            100f,
            SphereSlamPoseMath.dpiForReferenceWidth(1000, 0.254f),
            0.0001f,
        )
    }

    @Test
    fun dpiAndPageGeometry_roundTripSeveralPhysicalWidths() {
        val widthsMeters = listOf(0.25f, 1f, 2.5f, 12f)
        for (widthMeters in widthsMeters) {
            val dpi = SphereSlamPoseMath.dpiForReferenceWidth(
                referenceWidthPixels = 1600,
                referenceWidthMeters = widthMeters,
            )
            val geometry = SphereSlamPoseMath.pageGeometry(
                widthPixels = 1600,
                heightPixels = 900,
                referenceDpi = dpi,
            )
            assertEquals(widthMeters, geometry.widthMeters, 0.0001f)
        }
    }

    @Test
    fun pageGeometry_usesSameScaleAsKpmReferenceGeneration() {
        val geometry = SphereSlamPoseMath.pageGeometry(
            widthPixels = 1000,
            heightPixels = 500,
            referenceDpi = 100f,
        )
        assertEquals(0.254f, geometry.widthMeters, 0.0001f)
        assertEquals(0.127f, geometry.heightMeters, 0.0001f)
        assertEquals(127f, geometry.centerXmm, 0.0001f)
        assertEquals(63.5f, geometry.centerYmm, 0.0001f)
    }

    @Test
    fun pageToOpenGlViewMeters_matchesArtoolkitxHandednessAndMillimetreScale() {
        val kpm = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 1000f,
        )

        val actual = SphereSlamPoseMath.pageToOpenGlViewMeters(kpm)

        assertArrayEquals(
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, -1f, 0f, 0f,
                0f, 0f, -1f, 0f,
                0f, 0f, -1f, 1f,
            ),
            actual,
            0.0001f,
        )
    }

    @Test
    fun pageCenter_isAppliedInPageCoordinatesBeforeOpenGlConversion() {
        // +90 degrees about page Z in the KPM camera frame.
        val kpm = floatArrayOf(
            0f, -1f, 0f, 0f,
            1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
        )

        val actual = SphereSlamPoseMath.pageToOpenGlViewMeters(
            kpm,
            pageCenterXmm = 500f,
            pageCenterYmm = 250f,
        )

        // R * [500,250,0] = [-250,500,0] mm, then the OpenGL Y-row flip.
        assertEquals(-0.25f, actual[12], 0.0001f)
        assertEquals(-0.5f, actual[13], 0.0001f)
        assertEquals(0f, actual[14], 0.0001f)
    }
}
