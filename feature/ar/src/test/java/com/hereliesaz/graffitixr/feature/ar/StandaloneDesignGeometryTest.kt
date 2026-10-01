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
}
