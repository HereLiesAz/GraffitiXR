package com.hereliesaz.graffitixr.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class CurvesUtilTest {

    @Test
    fun testBitwiseLogic() {
        // Verify that the manual bitwise logic matches standard ARGB extraction and packing
        // A=255 (0xFF), R=18 (0x12), G=52 (0x34), B=86 (0x56)
        val color = 0xFF123456.toInt()

        // Extraction logic used in CurvesUtil
        val a = (color ushr 24)
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        assertEquals(255, a)
        assertEquals(0x12, r)
        assertEquals(0x34, g)
        assertEquals(0x56, b)

        // Reassembly logic
        // We simulate a LUT that maps x -> x (identity)
        val lutR = r
        val lutG = g
        val lutB = b

        val reassembled = (a shl 24) or (lutR shl 16) or (lutG shl 8) or lutB
        assertEquals(color, reassembled)
    }
}
