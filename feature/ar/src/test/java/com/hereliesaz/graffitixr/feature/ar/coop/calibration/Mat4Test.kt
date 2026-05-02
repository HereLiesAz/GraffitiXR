package com.hereliesaz.graffitixr.feature.ar.coop.calibration

import com.hereliesaz.graffitixr.common.sensor.Vec3
import org.junit.Assert.assertEquals
import org.junit.Test

class Mat4Test {
    @Test
    fun `identity apply preserves vector`() {
        val v = Vec3(1f, 2f, 3f)
        val out = Mat4.IDENTITY.apply(v)
        assertEquals(v, out)
    }

    @Test
    fun `translation applies offset`() {
        val m = Mat4.fromRotationTranslation(Mat3.IDENTITY, Vec3(10f, 20f, 30f))
        val out = m.apply(Vec3(1f, 1f, 1f))
        assertEquals(Vec3(11f, 21f, 31f), out)
    }

    @Test
    fun `identity has scale 1`() {
        assertEquals(1f, Mat4.IDENTITY.approximateScale(), 1e-6f)
    }
}
