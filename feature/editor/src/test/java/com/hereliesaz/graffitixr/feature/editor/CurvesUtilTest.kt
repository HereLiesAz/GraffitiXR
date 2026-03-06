package com.hereliesaz.graffitixr.feature.editor

import android.graphics.PointF
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class CurvesUtilTest {

    @Test
    fun `calculateAdjustmentCurve returns 256-element array`() {
        val points = listOf(0f to 0f, 1f to 1f)
        val lut = CurvesUtil.calculateAdjustmentCurveFromCoords(points)
        assertEquals(256, lut.size)
    }

    @Test
    fun `calculateAdjustmentCurve values are clamped to 0-255`() {
        val points = listOf(0f to 0f, 1f to 1f)
        val lut = CurvesUtil.calculateAdjustmentCurveFromCoords(points)
        for (i in 0..255) {
            assert(lut[i] in 0..255) { "lut[$i] = ${lut[i]} out of range" }
        }
    }

    @Test
    fun `calculateAdjustmentCurve identity points produce identity LUT`() {
        val points = listOf(0f to 0f, 1f to 1f)
        val lut = CurvesUtil.calculateAdjustmentCurveFromCoords(points)
        // Identity: input i should map to output i
        assertEquals(0, lut[0])
        assertEquals(255, lut[255])
    }
}
