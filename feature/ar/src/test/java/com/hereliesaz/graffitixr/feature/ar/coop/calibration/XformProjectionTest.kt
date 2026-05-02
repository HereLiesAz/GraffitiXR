package com.hereliesaz.graffitixr.feature.ar.coop.calibration

import com.hereliesaz.graffitixr.common.sensor.Vec3
import org.junit.Test

class XformProjectionTest {

    @Test
    fun `anchor in W_phone projected through xform lands in W_glasses`() {
        // 90-deg rotation around z + translation (1, 2, 3).
        val angle = (Math.PI / 2).toFloat()
        val rot = Mat3(
            kotlin.math.cos(angle), -kotlin.math.sin(angle), 0f,
            kotlin.math.sin(angle),  kotlin.math.cos(angle), 0f,
            0f, 0f, 1f,
        )
        val xform = Mat4.fromRotationTranslation(rot, Vec3(1f, 2f, 3f))

        val anchorPhoneFrame = Vec3(1f, 0f, 0f)
        // (cos90·1 - sin90·0)+1, (sin90·1 + cos90·0)+2, 0+3
        val expected = Vec3(1f, 3f, 3f)

        assertVec3Equals(expected, xform.apply(anchorPhoneFrame), 1e-5f)
    }
}

private fun assertVec3Equals(expected: Vec3, actual: Vec3, tolerance: Float) {
    org.junit.Assert.assertEquals(expected.x, actual.x, tolerance)
    org.junit.Assert.assertEquals(expected.y, actual.y, tolerance)
    org.junit.Assert.assertEquals(expected.z, actual.z, tolerance)
}
