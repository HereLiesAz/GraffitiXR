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

    @Test
    fun parseMeters_metricFlagMatchesTheLegacyOverload() {
        for (raw in listOf("2.5", " 2,5 ", "0.05", "100", "", "nope", "0.049", "100.01")) {
            assertEquals(
                raw,
                StandaloneReferenceScale.parseMeters(raw),
                StandaloneReferenceScale.parseMeters(raw, imperial = false),
            )
        }
    }

    @Test
    fun parseMeters_imperialBareNumberIsDecimalFeet() {
        assertEquals(3.048f, StandaloneReferenceScale.parseMeters("10", imperial = true)!!, 0.0001f)
        assertEquals(3.81f, StandaloneReferenceScale.parseMeters("12.5", imperial = true)!!, 0.0001f)
        assertEquals(3.81f, StandaloneReferenceScale.parseMeters(" 12,5 ", imperial = true)!!, 0.0001f)
    }

    @Test
    fun parseMeters_imperialFeetAndInchesSpellings() {
        // 12 ft 6 in = 150 in = 3.81 m
        for (raw in listOf("12'6\"", "12' 6\"", "12'6", "12 ft 6 in", "12ft6in", "12 feet 6 inches", "12′6″")) {
            assertEquals(raw, 3.81f, StandaloneReferenceScale.parseMeters(raw, imperial = true)!!, 0.0001f)
        }
        assertEquals(3.6576f, StandaloneReferenceScale.parseMeters("12'", imperial = true)!!, 0.0001f)
        assertEquals(1.9812f, StandaloneReferenceScale.parseMeters("78\"", imperial = true)!!, 0.0001f)
        assertEquals(1.9812f, StandaloneReferenceScale.parseMeters("78 in", imperial = true)!!, 0.0001f)
        assertEquals(0.0762f, StandaloneReferenceScale.parseMeters("3 inches", imperial = true)!!, 0.0001f)
    }

    @Test
    fun parseMeters_imperialRangeIsTheMetricRangeConverted() {
        // 2 in = 0.0508 m is just inside; 1 in = 0.0254 m is under the 0.05 m floor.
        assertEquals(0.0508f, StandaloneReferenceScale.parseMeters("2\"", imperial = true)!!, 0.0001f)
        assertNull(StandaloneReferenceScale.parseMeters("1\"", imperial = true))
        // 328 ft = 99.97 m is inside; 330 ft = 100.58 m is over the 100 m ceiling.
        assertEquals(99.9744f, StandaloneReferenceScale.parseMeters("328", imperial = true)!!, 0.001f)
        assertNull(StandaloneReferenceScale.parseMeters("330", imperial = true))
    }

    @Test
    fun parseMeters_imperialRejectsMalformedInput() {
        for (raw in listOf("", "  ", "nope", "NaN", "Infinity", "0", "-3", "12 6", "12'6'", "12\"6", "'", "\"", "ft", "1e400")) {
            assertNull(raw, StandaloneReferenceScale.parseMeters(raw, imperial = true))
        }
    }

    @Test
    fun rangeLabels_matchTheEnforcedRange() {
        assertEquals("0.05 m" to "100 m", StandaloneReferenceScale.rangeLabels(imperial = false))
        assertEquals("2 in" to "328 ft", StandaloneReferenceScale.rangeLabels(imperial = true))
    }
}
