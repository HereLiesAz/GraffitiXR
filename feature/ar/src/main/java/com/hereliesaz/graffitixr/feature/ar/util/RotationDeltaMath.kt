// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/util/RotationDeltaMath.kt
package com.hereliesaz.graffitixr.feature.ar.util

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure quaternion/rotation-matrix math behind [com.hereliesaz.graffitixr.feature.ar.GyroOrientationBridge]
 * — split out from it so this (the part that is easy to get subtly wrong) is unit-testable without a
 * device's actual sensors.
 *
 * Quaternions are `FloatArray(4) = [x, y, z, w]` throughout, matching
 * [com.hereliesaz.graffitixr.feature.ar.DeviceAttitudeProvider]'s existing convention. Rotation
 * matrices are `FloatArray(9)`, row-major (`m[row*3 + col]`) — chosen over this codebase's usual
 * column-major 4x4 GL convention because these are pure 3x3 rotations with no GL consumer of their
 * own; [GyroOrientationBridge] is the one place that folds a result back into a column-major 4x4.
 */
object RotationDeltaMath {

    /** Identity quaternion (no rotation). */
    val IDENTITY_QUATERNION = floatArrayOf(0f, 0f, 0f, 1f)

    /** Hamilton product `a * b` — applying the result rotates first by `b`, then by `a`. */
    fun multiplyQuaternions(a: FloatArray, b: FloatArray): FloatArray {
        val (ax, ay, az, aw) = a
        val (bx, by, bz, bw) = b
        return floatArrayOf(
            aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
            aw * bw - ax * bx - ay * by - az * bz,
        )
    }

    /** The inverse of a UNIT quaternion — its conjugate. Callers are responsible for normalizing. */
    fun conjugate(q: FloatArray): FloatArray = floatArrayOf(-q[0], -q[1], -q[2], q[3])

    /**
     * Renormalizes `q` to unit length. Sensor-fusion quaternions drift off unit length by a tiny
     * amount over many samples; every consumer here assumes unit length, so this is applied once at
     * the point each raw sample is ingested rather than trusted forever.
     */
    fun normalize(q: FloatArray): FloatArray {
        val n = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        if (n < 1e-9f) return IDENTITY_QUATERNION.copyOf()
        return floatArrayOf(q[0] / n, q[1] / n, q[2] / n, q[3] / n)
    }

    /** Unit quaternion `q` -> row-major 3x3 rotation matrix, `v' = R * v`. */
    fun toRotationMatrix3x3(q: FloatArray): FloatArray {
        val (x, y, z, w) = q
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        return floatArrayOf(
            1f - 2f * (yy + zz), 2f * (xy - wz), 2f * (xz + wy),
            2f * (xy + wz), 1f - 2f * (xx + zz), 2f * (yz - wx),
            2f * (xz - wy), 2f * (yz + wx), 1f - 2f * (xx + yy),
        )
    }

    /** Row-major 3x3 rotation about the shared camera/sensor optical (Z) axis, by `degrees`. */
    fun rotationAboutZ(degrees: Int): FloatArray {
        val rad = Math.toRadians(degrees.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        return floatArrayOf(
            c, -s, 0f,
            s, c, 0f,
            0f, 0f, 1f,
        )
    }

    /** `a * b` for row-major 3x3 matrices. */
    fun multiplyMat3(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (r in 0..2) for (c in 0..2) {
            var sum = 0f
            for (k in 0..2) sum += a[r * 3 + k] * b[k * 3 + c]
            out[r * 3 + c] = sum
        }
        return out
    }

    /** Transpose of a row-major 3x3 matrix — its inverse, since every matrix here is a rotation. */
    fun transposeMat3(m: FloatArray): FloatArray = floatArrayOf(
        m[0], m[3], m[6],
        m[1], m[4], m[7],
        m[2], m[5], m[8],
    )

    /** `m * v` for a row-major 3x3 matrix and a 3-vector. */
    fun multiplyMat3Vec3(m: FloatArray, v: FloatArray): FloatArray = floatArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )

    private operator fun FloatArray.component1() = this[0]
    private operator fun FloatArray.component2() = this[1]
    private operator fun FloatArray.component3() = this[2]
    private operator fun FloatArray.component4() = this[3]
}
