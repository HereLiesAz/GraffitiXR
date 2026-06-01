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
