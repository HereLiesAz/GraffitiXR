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
