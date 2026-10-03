// FILE: feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/GyroOrientationBridgeExtrinsicTest.kt
package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Closes the loop [GyroOrientationBridge]'s own doc flags as open: the fixed IMU-body→camera-optical
 * rotation `A = R_z(referenceRotationDeg)` is asserted but was never test-verified. `A` is applied as
 * a basis change `A · ΔR_body · Aᵀ` ([GyroOrientationBridge.cameraRotationDelta]); for a same-axis
 * (Z) delta the sign of `A` cancels, so only an OFF-axis body delta makes the sign observable. This
 * test drives an off-axis delta (about body X) and pins the sign to `R_z(+θ)` — the same convention
 * [com.hereliesaz.graffitixr.feature.ar.anchor.CaptureRotation] already verifies for the identical
 * `image.imageInfo.rotationDegrees` quantity — with a negative control proving `R_z(−θ)` is wrong.
 *
 * Reference matrices are written independently here (plain cos/sin, row-major `v' = R·v`), not taken
 * from `RotationDeltaMath`, so the thing under test cannot vacuously confirm itself.
 */
class GyroOrientationBridgeExtrinsicTest {

    private val eps = 1e-3f

    private fun bridge(): GyroOrientationBridge {
        // No real sensors in a JVM unit test: null the sensor service so construction is inert and
        // the test exercises only the math seam (ingestRotationQuaternion + commitReference +
        // cameraRotationDelta), never registerListener/SystemClock.
        val ctx = mockk<Context>()
        every { ctx.getSystemService(Context.SENSOR_SERVICE) } returns null
        return GyroOrientationBridge(ctx)
    }

    /** Row-major `R_z(+deg)` (CCW, `v' = R·v`) — the convention `rotationAboutZ` is claimed to use. */
    private fun rzPlus(deg: Int): FloatArray {
        val a = Math.toRadians(deg.toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        return floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f)
    }

    /** Row-major `R_x(deg)` (CCW, `v' = R·v`). */
    private fun rx(deg: Double): FloatArray {
        val a = Math.toRadians(deg)
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        return floatArrayOf(1f, 0f, 0f, 0f, c, -s, 0f, s, c)
    }

    private fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val o = FloatArray(9)
        for (r in 0..2) for (col in 0..2) {
            var sum = 0f
            for (k in 0..2) sum += a[r * 3 + k] * b[k * 3 + col]
            o[r * 3 + col] = sum
        }
        return o
    }

    private fun transpose(m: FloatArray) = floatArrayOf(
        m[0], m[3], m[6],
        m[1], m[4], m[7],
        m[2], m[5], m[8],
    )

    private fun conjugation(theta: Int, delta: FloatArray): FloatArray {
        val a = rzPlus(theta)
        return mul(mul(a, delta), transpose(a))
    }

    /** Quaternion [x,y,z,w] for a rotation of `deg` about body +X. */
    private fun quatX(deg: Double): FloatArray {
        val half = Math.toRadians(deg).toFloat() / 2f
        return floatArrayOf(sin(half), 0f, 0f, cos(half))
    }

    private val identityQuat = floatArrayOf(0f, 0f, 0f, 1f)

    private fun maxGap(a: FloatArray, b: FloatArray) = (0..8).maxOf { abs(a[it] - b[it]) }

    @Test
    fun `A conjugates an off-axis body delta by R_z of plus referenceRotationDeg`() {
        val phi = 30.0
        // Reference = identity; now = +phi about body X. ΔR_body = conj(qNow)·qRef = R_x(-phi).
        val deltaBody = rx(-phi)

        for (theta in intArrayOf(90, 270)) {
            val b = bridge()
            b.commitReference(GyroOrientationBridge.ReferenceCandidate(identityQuat, theta, 0L))
            b.ingestRotationQuaternion(quatX(phi)[0], quatX(phi)[1], quatX(phi)[2], quatX(phi)[3])

            val actual = b.cameraRotationDelta()
                ?: error("cameraRotationDelta returned null with a committed reference and a sample")

            val expected = conjugation(theta, deltaBody)
            for (i in 0..8) {
                assertEquals("theta=$theta element $i", expected[i], actual[i], eps)
            }
        }
    }

    @Test
    fun `the opposite sign is measurably wrong at the quarter turns`() {
        val phi = 30.0
        val deltaBody = rx(-phi)

        for (theta in intArrayOf(90, 270)) {
            val b = bridge()
            b.commitReference(GyroOrientationBridge.ReferenceCandidate(identityQuat, theta, 0L))
            b.ingestRotationQuaternion(quatX(phi)[0], quatX(phi)[1], quatX(phi)[2], quatX(phi)[3])

            val actual = b.cameraRotationDelta()!!
            val wrong = conjugation(-theta, deltaBody)
            val gap = maxGap(actual, wrong)
            assertTrue("R_z(-$theta) must NOT match; gap was $gap", gap > 0.05f)
        }
    }

    @Test
    fun `no reference or no sample yields null`() {
        val noSample = bridge()
        noSample.commitReference(GyroOrientationBridge.ReferenceCandidate(identityQuat, 90, 0L))
        assertEquals(null, noSample.cameraRotationDelta()) // reference set, but no live sample yet

        val noRef = bridge()
        noRef.ingestRotationQuaternion(0f, 0f, 0f, 1f)
        assertEquals(null, noRef.cameraRotationDelta()) // sample set, but no committed reference
    }
}
