package com.hereliesaz.graffitixr.feature.editor

import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Test

class CurvesUtilTest {

    @Test
    fun `calculateAdjustmentCurve returns 256-element array`() {
        val points = listOf(PointF(0f, 0f), PointF(1f, 1f))
        val lut = CurvesUtil.calculateAdjustmentCurve(points)
        assertEquals(256, lut.size)
    }

    @Test
    fun `calculateAdjustmentCurve values are clamped to 0-255`() {
        val points = listOf(PointF(0f, 0f), PointF(1f, 1f))
        val lut = CurvesUtil.calculateAdjustmentCurve(points)
        for (i in 0..255) {
            assert(lut[i] in 0..255) { "lut[$i] = ${lut[i]} out of range" }
        }
    }

    @Test
    fun `calculateAdjustmentCurve identity points produce identity LUT`() {
        val points = listOf(PointF(0f, 0f), PointF(1f, 1f))
        val lut = CurvesUtil.calculateAdjustmentCurve(points)
        // Identity: input i should map to output i
        assertEquals(0, lut[0])
        assertEquals(255, lut[255])
    }
}
