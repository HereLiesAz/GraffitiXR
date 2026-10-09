package com.hereliesaz.graffitixr.feature.ar.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HUD must never print the native "not measured" sentinel as if it were a duration.
 * Expected strings are literals, not built with the formatter's own format call.
 */
class StageTimingsTest {

    @Test
    fun `native shape with four dead slots shows only pnpReloc`() {
        assertEquals("12.3", StageTimings.formatHud(floatArrayOf(-1f, -1f, -1f, -1f, 12.34f)))
    }

    @Test
    fun `unmeasured pnpReloc renders a dash, not -1`() {
        assertEquals("—", StageTimings.formatHud(floatArrayOf(-1f, -1f, -1f, -1f, -1f)))
    }

    @Test
    fun `zero is a real measurement`() {
        assertEquals("0.0", StageTimings.formatHud(floatArrayOf(-1f, -1f, -1f, -1f, 0f)))
    }

    @Test
    fun `short array renders a dash instead of throwing`() {
        assertEquals("—", StageTimings.formatHud(floatArrayOf(3f, 4f)))
        assertEquals("—", StageTimings.formatHud(FloatArray(0)))
    }

    @Test
    fun `isMeasured rejects sentinel and NaN`() {
        assertFalse(StageTimings.isMeasured(-1f))
        assertFalse(StageTimings.isMeasured(Float.NaN))
        assertTrue(StageTimings.isMeasured(0f))
        assertTrue(StageTimings.isMeasured(7.5f))
    }

    @Test
    fun `pnpReloc index matches the CSV column contract`() {
        assertEquals("pnpRelocMs", EvalSampleLog.COLUMNS[EvalSampleLog.COLUMNS.indexOf("voxelUpdateMs") + 4])
        assertEquals(4, StageTimings.PNP_RELOC)
    }
}
