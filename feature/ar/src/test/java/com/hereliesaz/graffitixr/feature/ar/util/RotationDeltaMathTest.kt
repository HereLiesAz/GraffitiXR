package com.hereliesaz.graffitixr.feature.ar.util

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-math correctness for [RotationDeltaMath] — the part of
 * [com.hereliesaz.graffitixr.feature.ar.GyroOrientationBridge] that is easy to get subtly wrong
 * and, unlike the sensor/native pieces around it, fully testable on the JVM.
 */
class RotationDeltaMathTest {

    private val eps = 1e-4f

    private fun assertMatEquals(expected: FloatArray, actual: FloatArray, tolerance: Float = eps) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertTrue(
                "index $i: expected ${expected[i]}, was ${actual[i]}",
                abs(expected[i] - actual[i]) < tolerance,
            )
        }
    }

    private fun quaternionAboutAxis(axis: FloatArray, radians: Double): FloatArray {
        val half = radians / 2.0
        val s = sin(half).toFloat()
        return RotationDeltaMath.normalize(
            floatArrayOf(axis[0] * s, axis[1] * s, axis[2] * s, cos(half).toFloat()),
        )
    }

    @Test
    fun `identity quaternion produces the identity matrix`() {
        val identity3x3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertMatEquals(identity3x3, RotationDeltaMath.toRotationMatrix3x3(RotationDeltaMath.IDENTITY_QUATERNION))
    }

    @Test
    fun `90 degree quaternion about Z matches rotationAboutZ`() {
        val q = quaternionAboutAxis(floatArrayOf(0f, 0f, 1f), PI / 2.0)
        assertMatEquals(RotationDeltaMath.rotationAboutZ(90), RotationDeltaMath.toRotationMatrix3x3(q))
    }

    @Test
    fun `rotationAboutZ(0) is the identity`() {
        val identity3x3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertMatEquals(identity3x3, RotationDeltaMath.rotationAboutZ(0))
    }

    @Test
    fun `rotationAboutZ(360) is the identity`() {
        val identity3x3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertMatEquals(identity3x3, RotationDeltaMath.rotationAboutZ(360), tolerance = 1e-3f)
    }

    @Test
    fun `conjugate of a unit quaternion is its inverse`() {
        val q = quaternionAboutAxis(floatArrayOf(1f, 2f, 3f).let { v ->
            val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            floatArrayOf(v[0] / n, v[1] / n, v[2] / n)
        }, 1.234)
        val shouldBeIdentity = RotationDeltaMath.multiplyQuaternions(RotationDeltaMath.conjugate(q), q)
        assertMatEquals(RotationDeltaMath.IDENTITY_QUATERNION, shouldBeIdentity)
    }

    @Test
    fun `quaternion multiplication order matches matrix multiplication order`() {
        val qa = quaternionAboutAxis(floatArrayOf(0f, 0f, 1f), PI / 2.0)
        val qb = quaternionAboutAxis(floatArrayOf(0f, 1f, 0f), PI / 3.0)

        val viaQuaternion = RotationDeltaMath.toRotationMatrix3x3(
            RotationDeltaMath.normalize(RotationDeltaMath.multiplyQuaternions(qa, qb)),
        )
        val viaMatrix = RotationDeltaMath.multiplyMat3(
            RotationDeltaMath.toRotationMatrix3x3(qa),
            RotationDeltaMath.toRotationMatrix3x3(qb),
        )
        assertMatEquals(viaMatrix, viaQuaternion, tolerance = 1e-3f)
    }

    @Test
    fun `transpose of a rotation matrix is its inverse`() {
        val m = RotationDeltaMath.rotationAboutZ(37)
        val shouldBeIdentity = RotationDeltaMath.multiplyMat3(m, RotationDeltaMath.transposeMat3(m))
        val identity3x3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertMatEquals(identity3x3, shouldBeIdentity)
    }

    @Test
    fun `multiplyMat3Vec3 rotates a vector the same way multiplyMat3 rotates a matrix column`() {
        // 90 degrees about Z: (x,y,z) -> (-y,x,z). rotationAboutZ is [[c,-s,0],[s,c,0],[0,0,1]].
        val rot = RotationDeltaMath.rotationAboutZ(90)
        val v = floatArrayOf(1f, 0f, 0f)
        val rotated = RotationDeltaMath.multiplyMat3Vec3(rot, v)
        assertMatEquals(floatArrayOf(0f, 1f, 0f), rotated)
    }

    @Test
    fun `multiplyMat3Vec3 by the identity matrix leaves the vector unchanged`() {
        val identity3x3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val v = floatArrayOf(3f, -2f, 5f)
        assertMatEquals(v, RotationDeltaMath.multiplyMat3Vec3(identity3x3, v))
    }

    @Test
    fun `a rotation delta between a quaternion and itself is the identity`() {
        val q = quaternionAboutAxis(floatArrayOf(0.267f, 0.535f, 0.802f), 0.77)
        val delta = RotationDeltaMath.multiplyQuaternions(RotationDeltaMath.conjugate(q), q)
        assertMatEquals(RotationDeltaMath.IDENTITY_QUATERNION, delta)
    }

    @Test
    fun `normalize renormalizes a drifted-length quaternion without changing its direction`() {
        val q = floatArrayOf(0f, 0f, 0f, 2f) // identity direction, wrong length
        val n = RotationDeltaMath.normalize(q)
        assertMatEquals(RotationDeltaMath.IDENTITY_QUATERNION, n)
    }

    @Test
    fun `normalize of a near-zero quaternion falls back to identity rather than dividing by zero`() {
        val n = RotationDeltaMath.normalize(floatArrayOf(0f, 0f, 0f, 0f))
        assertMatEquals(RotationDeltaMath.IDENTITY_QUATERNION, n)
    }
}
