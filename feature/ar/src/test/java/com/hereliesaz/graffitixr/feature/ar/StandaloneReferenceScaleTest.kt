package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StandaloneReferenceScaleTest {

    @Test
    fun parseMeters_acceptsUsefulPhysicalWidths() {
        assertEquals(0.05f, StandaloneReferenceScale.parseMeters("0.05")!!, 0.0001f)
        assertEquals(2.5f, StandaloneReferenceScale.parseMeters("2.5")!!, 0.0001f)
        assertEquals(2.5f, StandaloneReferenceScale.parseMeters(" 2,5 ")!!, 0.0001f)
        assertEquals(100f, StandaloneReferenceScale.parseMeters("100")!!, 0.0001f)
    }

    @Test
    fun parseMeters_rejectsNonFiniteNonPositiveAndImplausibleWidths() {
        for (raw in listOf("", "nope", "NaN", "Infinity", "0", "-1", "0.049", "100.01")) {
            assertNull(raw, StandaloneReferenceScale.parseMeters(raw))
        }
    }
}
