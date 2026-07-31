package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class PoseMathTest {
    private fun identity() = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)

    @Test fun `multiply by identity returns original`() {
        val m = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 5f,6f,7f,1f) // translate (5,6,7)
        assertEquals(m.toList(), PoseMath.multiply(m, identity()).toList())
        assertEquals(m.toList(), PoseMath.multiply(identity(), m).toList())
    }

    /**
     * The identity and inverse tests above both COMMUTE (A*I == I*A, M⁻¹*M == M*M⁻¹), so an
     * implementation of multiply(a, b) that returned b*a passes every one of them. Argument order is
     * the thing the whole PoseFusion composition depends on, so pin it with operands that do not
     * commute: rotate-then-translate is not translate-then-rotate.
     */
    @Test fun `multiply is not commutative and applies b in a's frame`() {
        // 90 deg about Z (column-major: local +X maps to world +Y).
        val rot = floatArrayOf(0f,1f,0f,0f, -1f,0f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)
        val tr = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 2f,0f,0f,1f) // translate +2 along X

        // multiply(rot, tr): the translation happens in rot's frame, so +2 along local X lands at
        // world (0, 2, 0).
        val rotThenTr = PoseMath.multiply(rot, tr)
        assertEquals(0f, rotThenTr[12], 1e-4f)
        assertEquals(2f, rotThenTr[13], 1e-4f)

        // multiply(tr, rot): the translation is applied in world, so the origin stays at (2, 0, 0).
        val trThenRot = PoseMath.multiply(tr, rot)
        assertEquals(2f, trThenRot[12], 1e-4f)
        assertEquals(0f, trThenRot[13], 1e-4f)
    }

    @Test fun `rigidInverse undoes a translation+rotation`() {
        // 90 deg about Z then translate (1,2,3). inverse(M)*M == identity.
        val c = 0f; val s = 1f
        val m = floatArrayOf(c,s,0f,0f, -s,c,0f,0f, 0f,0f,1f,0f, 1f,2f,3f,1f)
        val prod = PoseMath.multiply(PoseMath.rigidInverse(m), m)
        identity().forEachIndexed { i, e -> assertEquals(e, prod[i], 1e-4f) }
    }

    /** Rotation of [deg] about an arbitrary (normalized) axis — deliberately NOT about Z. */
    private fun axisRot(ax: Float, ay: Float, az: Float, deg: Float): FloatArray {
        val n = sqrt(ax * ax + ay * ay + az * az)
        val x = ax / n; val y = ay / n; val z = az / n
        val c = kotlin.math.cos(Math.toRadians(deg.toDouble())).toFloat()
        val s = kotlin.math.sin(Math.toRadians(deg.toDouble())).toFloat()
        val t = 1f - c
        // Row-major R, written into column-major storage.
        val r = floatArrayOf(
            t*x*x + c,   t*x*y + s*z, t*x*z - s*y, 0f,
            t*x*y - s*z, t*y*y + c,   t*y*z + s*x, 0f,
            t*x*z + s*y, t*y*z - s*x, t*z*z + c,   0f,
            0f, 0f, 0f, 1f,
        )
        return r
    }

    private fun withTranslation(m: FloatArray, tx: Float, ty: Float, tz: Float) =
        m.copyOf().also { it[12] = tx; it[13] = ty; it[14] = tz }

    /** Scale columns 0..[cols)-1 of the linear part, mimicking `Matrix.scaleM`. */
    private fun scaleCols(m: FloatArray, s: Float, cols: Int) = m.copyOf().also {
        for (c in 0 until cols) for (row in 0 until 3) it[c * 4 + row] *= s
    }

    @Test fun `scaleOf returns one for a rigid matrix and s for a scaled one`() {
        assertEquals(1f, PoseMath.scaleOf(identity()), 1e-5f)
        val rot = axisRot(0.3f, -0.7f, 0.6f, 37f)
        assertEquals(1f, PoseMath.scaleOf(rot), 1e-5f)
        assertEquals(2.5f, PoseMath.scaleOf(scaleCols(rot, 2.5f, 3)), 1e-4f)
        // Reading the FIRST column means an in-plane-only scale still reports s.
        assertEquals(2.5f, PoseMath.scaleOf(scaleCols(rot, 2.5f, 2)), 1e-4f)
    }

    /**
     * For a genuinely uniform similarity the inverse must be exact — and with a rotation about an
     * arbitrary axis, not just Z. Every anchor in `FootprintTest` rotates about Z, which leaves the
     * inverse's third column trivially zero and would hide an error there.
     */
    @Test fun `similarityInverse is exact for a uniform scale and an arbitrary axis`() {
        val m = withTranslation(scaleCols(axisRot(0.3f, -0.7f, 0.6f, 37f), 2.5f, 3), 1.5f, -2f, 4f)
        val prod = PoseMath.multiply(PoseMath.similarityInverse(m), m)
        identity().forEachIndexed { i, e -> assertEquals("element $i", e, prod[i], 1e-4f) }
    }

    /**
     * The exactness caveat in `similarityInverse`'s KDoc, asserted rather than merely asserted-in-
     * prose: for the overlay's `scaleM(s, s, 1f)` the computed inverse is EXACT in rows 0 and 1 and
     * wrong in row 2 by a factor of `1/s²`. Footprint reads only rows 0 and 1, which is what makes
     * it safe there — and this test is what stops someone reusing it where row 2 matters.
     */
    @Test fun `similarityInverse is exact in-plane and off by one over s-squared on the normal row`() {
        val s = 2.5f
        val rot = axisRot(0.3f, -0.7f, 0.6f, 37f)
        val m = scaleCols(rot, s, 2)                       // diag(s, s, 1), not uniform
        val got = PoseMath.similarityInverse(m)

        // True inverse of R*diag(s,s,1) is diag(1/s,1/s,1)*R^T.
        val true0 = FloatArray(16).also {
            for (i in 0 until 3) for (j in 0 until 3) {
                it[j * 4 + i] = rot[i * 4 + j] * if (i < 2) 1f / s else 1f
            }
            it[15] = 1f
        }
        for (j in 0 until 3) {
            assertEquals("row 0 col $j must be exact", true0[j * 4], got[j * 4], 1e-4f)
            assertEquals("row 1 col $j must be exact", true0[j * 4 + 1], got[j * 4 + 1], 1e-4f)
            // Row 2 is scaled by 1/s^2 relative to truth — documented, and relied on being harmless.
            assertEquals("row 2 col $j must be off by 1/s^2",
                true0[j * 4 + 2] / (s * s), got[j * 4 + 2], 1e-4f)
        }
    }

    @Test fun `similarityInverse rejects a degenerate scale instead of dividing by zero`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            PoseMath.similarityInverse(FloatArray(16))
        }
    }

    @Test fun `quaternion round-trips through matrix`() {
        // 90 deg about Z
        val q = PoseMath.matrixToQuaternion(floatArrayOf(0f,1f,0f,0f, -1f,0f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f))
        val m = PoseMath.fromQuaternionTranslation(q, floatArrayOf(0f,0f,0f))
        assertEquals(0f, m[0], 1e-4f); assertEquals(1f, m[1], 1e-4f)
        assertEquals(-1f, m[4], 1e-4f); assertEquals(0f, m[5], 1e-4f)
    }

    @Test fun `nlerp at 0 and 1 returns endpoints`() {
        val a = floatArrayOf(0f,0f,0f,1f); val b = PoseMath.matrixToQuaternion(
            floatArrayOf(0f,1f,0f,0f, -1f,0f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f))
        PoseMath.nlerpQuat(a, b, 0f).forEachIndexed { i, e -> assertEquals(a[i], e, 1e-4f) }
        val n = b.let { val l = sqrt(it[0]*it[0]+it[1]*it[1]+it[2]*it[2]+it[3]*it[3]); floatArrayOf(it[0]/l,it[1]/l,it[2]/l,it[3]/l) }
        PoseMath.nlerpQuat(a, b, 1f).forEachIndexed { i, e -> assertEquals(n[i], e, 1e-4f) }
    }
}
