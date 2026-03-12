package com.hereliesaz.graffitixr.feature.editor

import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Test

class CurvesUtilTest {

    private fun pointF(x: Float, y: Float) = PointF().also { it.x = x; it.y = y }

    @Test
    fun `calculateAdjustmentCurve returns 256-element array`() {
        val points = listOf(pointF(0f, 0f), pointF(1f, 1f))
        val lut = CurvesUtil.calculateAdjustmentCurve(points)
        assertEquals(256, lut.size)
    }

    @Test
    fun `calculateAdjustmentCurve values are clamped to 0-255`() {
        val points = listOf(pointF(0f, 0f), pointF(1f, 1f))
        val lut = CurvesUtil.calculateAdjustmentCurve(points)
        for (i in 0..255) {
            assert(lut[i] in 0..255) { "lut[$i] = ${lut[i]} out of range" }
        }
    }

    @Test
    fun `calculateAdjustmentCurve identity points produce identity LUT`() {
        val points = listOf(pointF(0f, 0f), pointF(1f, 1f))
        val lut = CurvesUtil.calculateAdjustmentCurve(points)
        assertEquals(0, lut[0])
        assertEquals(255, lut[255])
    }
}
