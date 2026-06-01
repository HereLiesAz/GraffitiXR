package com.hereliesaz.graffitixr.feature.ar.anchor

import kotlin.math.sqrt

/** Pure column-major 4x4 (OpenGL/ARCore layout) helpers for rigid transforms. No Android deps. */
object PoseMath {

    /** Column-major 4x4 multiply: returns a*b. */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (col in 0 until 4) for (row in 0 until 4) {
            var sum = 0f
            for (k in 0 until 4) sum += a[k * 4 + row] * b[col * 4 + k]
            r[col * 4 + row] = sum
        }
        return r
    }

    /** Inverse of a rigid transform [R|t] (rotation+translation, no scale): [R^T | -R^T t]. */
    fun rigidInverse(m: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (i in 0 until 3) for (j in 0 until 3) r[j * 4 + i] = m[i * 4 + j]
        val tx = m[12]; val ty = m[13]; val tz = m[14]
        r[12] = -(r[0] * tx + r[4] * ty + r[8] * tz)
        r[13] = -(r[1] * tx + r[5] * ty + r[9] * tz)
        r[14] = -(r[2] * tx + r[6] * ty + r[10] * tz)
        r[15] = 1f
        return r
    }

    fun translationOf(m: FloatArray) = floatArrayOf(m[12], m[13], m[14])

    /** Extract a unit quaternion (x,y,z,w) from the rotation part of a column-major matrix. */
    fun matrixToQuaternion(m: FloatArray): FloatArray {
        val m00 = m[0]; val m10 = m[1]; val m20 = m[2]
        val m01 = m[4]; val m11 = m[5]; val m21 = m[6]
        val m02 = m[8]; val m12 = m[9]; val m22 = m[10]
        val trace = m00 + m11 + m22
        val q = FloatArray(4)
        if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            q[3] = 0.25f * s; q[0] = (m21 - m12) / s; q[1] = (m02 - m20) / s; q[2] = (m10 - m01) / s
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1f + m00 - m11 - m22) * 2f
            q[3] = (m21 - m12) / s; q[0] = 0.25f * s; q[1] = (m01 + m10) / s; q[2] = (m02 + m20) / s
        } else if (m11 > m22) {
            val s = sqrt(1f + m11 - m00 - m22) * 2f
            q[3] = (m02 - m20) / s; q[0] = (m01 + m10) / s; q[1] = 0.25f * s; q[2] = (m12 + m21) / s
        } else {
            val s = sqrt(1f + m22 - m00 - m11) * 2f
            q[3] = (m10 - m01) / s; q[0] = (m02 + m20) / s; q[1] = (m12 + m21) / s; q[2] = 0.25f * s
        }
        return normalizeQuat(q)
    }

    fun normalizeQuat(q: FloatArray): FloatArray {
        val l = sqrt(q[0]*q[0] + q[1]*q[1] + q[2]*q[2] + q[3]*q[3])
        return if (l == 0f) floatArrayOf(0f,0f,0f,1f) else floatArrayOf(q[0]/l, q[1]/l, q[2]/l, q[3]/l)
    }

    /** Build a column-major matrix from a unit quaternion (x,y,z,w) and a translation. */
    fun fromQuaternionTranslation(q: FloatArray, t: FloatArray): FloatArray {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val m = FloatArray(16)
        m[0] = 1 - 2*(y*y + z*z); m[1] = 2*(x*y + z*w);     m[2] = 2*(x*z - y*w)
        m[4] = 2*(x*y - z*w);     m[5] = 1 - 2*(x*x + z*z); m[6] = 2*(y*z + x*w)
        m[8] = 2*(x*z + y*w);     m[9] = 2*(y*z - x*w);     m[10] = 1 - 2*(x*x + y*y)
        m[12] = t[0]; m[13] = t[1]; m[14] = t[2]; m[15] = 1f
        return m
    }

    /** Normalized lerp between unit quaternions, hemisphere-corrected. t in [0,1]. */
    fun nlerpQuat(a: FloatArray, b: FloatArray, t: Float): FloatArray {
        val dot = a[0]*b[0] + a[1]*b[1] + a[2]*b[2] + a[3]*b[3]
        val s = if (dot < 0f) -1f else 1f
        return normalizeQuat(floatArrayOf(
            a[0] + (b[0]*s - a[0]) * t,
            a[1] + (b[1]*s - a[1]) * t,
            a[2] + (b[2]*s - a[2]) * t,
            a[3] + (b[3]*s - a[3]) * t,
        ))
    }

    fun lerp(a: FloatArray, b: FloatArray, t: Float) =
        floatArrayOf(a[0]+(b[0]-a[0])*t, a[1]+(b[1]-a[1])*t, a[2]+(b[2]-a[2])*t)
}
