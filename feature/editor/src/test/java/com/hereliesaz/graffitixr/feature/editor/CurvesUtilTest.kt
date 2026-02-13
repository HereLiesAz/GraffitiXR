package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class CurvesUtilTest {

    @Test
    fun `createLut generates identity LUT for identity points`() {
        val points = listOf(Offset(0f, 0f), Offset(1f, 1f))
        val lut = createLut(points)

        for (i in 0..255) {
            assertEquals(i, lut[i])
        }
    }

    @Test
    fun `createLut generates inverted LUT for inverted points`() {
        val points = listOf(Offset(0f, 1f), Offset(1f, 0f))
        val lut = createLut(points)

        for (i in 0..255) {
            assertEquals((255 - i).toFloat(), lut[i].toFloat(), 1.0f)
        }
    }

    @Test
    fun `createLut handles midpoint adjustment`() {
        // Curve passing through (0,0), (0.5, 0.8), (1,1) -> brighter image
        val points = listOf(Offset(0f, 0f), Offset(0.5f, 0.8f), Offset(1f, 1f))
        val lut = createLut(points)

        // At 0.5 input (127), output should be around 0.8 * 255 = 204
        val midInput = 127
        val expectedOutput = (0.8f * 255).toInt()
        
        // Allow some tolerance due to interpolation
        assertEquals(expectedOutput.toFloat(), lut[midInput].toFloat(), 2f)
    }
}
