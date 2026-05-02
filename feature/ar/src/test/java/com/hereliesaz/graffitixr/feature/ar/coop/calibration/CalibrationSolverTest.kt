package com.hereliesaz.graffitixr.feature.ar.coop.calibration

import com.hereliesaz.graffitixr.common.sensor.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class CalibrationSolverTest {

    @Test
    fun `recovers identity from identical inputs`() {
        val src = listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, 0f, 1f))
        val out = Procrustes.solve(src, src)
        assertNotNull(out)
        val transformed = src.map { out!!.apply(it) }
        for (i in src.indices) {
            assertVec3Equals(src[i], transformed[i], 1e-3f)
        }
    }

    @Test
    fun `recovers pure translation`() {
        val src = listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, 0f, 1f))
        val offset = Vec3(5f, -3f, 2f)
        val dst = src.map { it + offset }
        val out = Procrustes.solve(src, dst)
        assertNotNull(out)
        for (i in src.indices) {
            assertVec3Equals(dst[i], out!!.apply(src[i]), 1e-2f)
        }
    }

    @Test
    fun `recovers known rotation around z-axis`() {
        val angle = 0.5f
        val rot = Mat3(
            cos(angle), -sin(angle), 0f,
            sin(angle),  cos(angle), 0f,
            0f, 0f, 1f,
        )
        val src = (1..10).map { Vec3(it.toFloat(), it * 0.3f, it * -0.2f) }
        val dst = src.map { rot * it }
        val out = Procrustes.solve(src, dst)
        assertNotNull(out)
        for (i in src.indices) {
            assertVec3Equals(dst[i], out!!.apply(src[i]), 5e-2f)
        }
    }

    @Test
    fun `recovers under small Gaussian noise`() {
        val rng = Random(42)
        val angle = 0.3f
        val rot = Mat3(cos(angle), 0f, sin(angle), 0f, 1f, 0f, -sin(angle), 0f, cos(angle))
        val t = Vec3(1f, 2f, 3f)
        val src = (1..20).map { Vec3(it * 0.1f, it * -0.1f, it * 0.05f) }
        val dst = src.map { (rot * it) + t + Vec3(rng.nextFloat() * 0.005f, rng.nextFloat() * 0.005f, rng.nextFloat() * 0.005f) }

        val out = Procrustes.solve(src, dst)
        assertNotNull(out)
        val errors = src.indices.map { i ->
            val transformed = out!!.apply(src[i])
            val d = transformed - dst[i]
            d.length()
        }
        assertEquals(0.0, errors.average(), 0.05)
    }
}

private fun assertVec3Equals(expected: Vec3, actual: Vec3, tolerance: Float) {
    org.junit.Assert.assertEquals(expected.x, actual.x, tolerance)
    org.junit.Assert.assertEquals(expected.y, actual.y, tolerance)
    org.junit.Assert.assertEquals(expected.z, actual.z, tolerance)
}
