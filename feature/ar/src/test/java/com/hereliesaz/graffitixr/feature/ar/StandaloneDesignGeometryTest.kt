package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StandaloneDesignGeometryTest {

    @Test
    fun landscapeDesignFitsByPageWidth() {
        val fit = requireNotNull(
            fitStandaloneDesignHalfExtents(
                pageWidthUnits = 2f,
                pageHeightUnits = 2f,
                designWidthPx = 200,
                designHeightPx = 100,
            ),
        )
        assertEquals(1f, fit.halfWidth, 0.0001f)
        assertEquals(0.5f, fit.halfHeight, 0.0001f)
    }

    @Test
    fun portraitDesignFitsByPageHeight() {
        val fit = requireNotNull(
            fitStandaloneDesignHalfExtents(
                pageWidthUnits = 2f,
                pageHeightUnits = 1f,
                designWidthPx = 100,
                designHeightPx = 200,
            ),
        )
        assertEquals(0.25f, fit.halfWidth, 0.0001f)
        assertEquals(0.5f, fit.halfHeight, 0.0001f)
    }

    @Test
    fun noDesignUsesWholeReferencePage() {
        val fit = requireNotNull(
            fitStandaloneDesignHalfExtents(2f, 1f, null, null),
        )
        assertEquals(1f, fit.halfWidth, 0.0001f)
        assertEquals(0.5f, fit.halfHeight, 0.0001f)
    }

    @Test
    fun invalidPageGeometryIsRejected() {
        assertNull(fitStandaloneDesignHalfExtents(Float.NaN, 1f, 100, 100))
        assertNull(fitStandaloneDesignHalfExtents(1f, 0f, 100, 100))
    }

    @Test
    fun standalonePlacement_usesRigidPanAndClockwiseToGlRotation() {
        val base = StandaloneDesignHalfExtents(1f, 0.5f)
        val placement = requireNotNull(
            standaloneDesignPlacement(
                base = base,
                panX = 0.25f,
                panY = -0.4f,
                scale = 2f,
                storedClockwiseRotationDeg = 90f,
            ),
        )

        // Stored +90 CW becomes -90 in the right-handed wall frame.
        assertEquals(0f, placement.fingerprintFromDesign[0], 0.0001f)
        assertEquals(-1f, placement.fingerprintFromDesign[1], 0.0001f)
        assertEquals(1f, placement.fingerprintFromDesign[4], 0.0001f)
        assertEquals(0f, placement.fingerprintFromDesign[5], 0.0001f)
        assertEquals(0.25f, placement.fingerprintFromDesign[12], 0.0001f)
        assertEquals(-0.4f, placement.fingerprintFromDesign[13], 0.0001f)
        // Scale belongs only in the extents.
        assertEquals(2f, placement.halfWidth, 0.0001f)
        assertEquals(1f, placement.halfHeight, 0.0001f)
    }

    @Test
    fun standalonePlacement_rejectsNonFiniteTransform() {
        val base = StandaloneDesignHalfExtents(1f, 1f)
        assertNull(standaloneDesignPlacement(base, Float.NaN, 0f, 1f, 0f))
        assertNull(standaloneDesignPlacement(base, 0f, 0f, Float.NaN, 0f))
    }

}
