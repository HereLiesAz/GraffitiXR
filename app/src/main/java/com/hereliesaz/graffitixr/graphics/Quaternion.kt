package com.hereliesaz.graffitixr.graphics

import android.opengl.Matrix
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * A data class representing a quaternion for 3D rotations.
 * Quaternions are used to avoid issues like gimbal lock that can occur with Euler angles.
 *
 * @property x The x component of the vector part.
 * @property y The y component of the vector part.
 * @property z The z component of the vector part.
 * @property w The scalar part.
 */
@Parcelize
data class Quaternion(val x: Float, val y: Float, val z: Float, val w: Float) : Parcelable {

    /**
     * Combines this quaternion with another, resulting in a new rotation.
     * Note: Quaternion multiplication is not commutative.
     */
    operator fun times(q: Quaternion): Quaternion {
        return Quaternion(
            w * q.x + x * q.w + y * q.z - z * q.y,
            w * q.y - x * q.z + y * q.w + z * q.x,
            w * q.z + x * q.y - y * q.x + z * q.w,
            w * q.w - x * q.x - y * q.y - z * q.z
        )
    }

    /**
     * Converts this quaternion to a 4x4 rotation matrix.
     *
     * @return A 16-element float array representing the rotation matrix.
     */
    fun toRotationMatrix(): FloatArray {
        val matrix = FloatArray(16)
        Matrix.setIdentityM(matrix, 0)

        val xx = x * x
        val xy = x * y
        val xz = x * z
        val xw = x * w
        val yy = y * y
        val yz = y * z
        val yw = y * w
        val zz = z * z
        val zw = z * w

        matrix[0] = 1 - 2 * (yy + zz)
        matrix[1] = 2 * (xy + zw)
        matrix[2] = 2 * (xz - yw)
        matrix[4] = 2 * (xy - zw)
        matrix[5] = 1 - 2 * (xx + zz)
        matrix[6] = 2 * (yz + xw)
        matrix[8] = 2 * (xz + yw)
        matrix[9] = 2 * (yz - xw)
        matrix[10] = 1 - 2 * (xx + yy)

        return matrix
    }

    companion object {
        /**
         * Creates a new identity quaternion (no rotation).
         */
        fun identity() = Quaternion(0f, 0f, 0f, 1f)

        /**
         * Creates a quaternion from an axis and an angle.
         *
         * @param axis The axis of rotation (must be a normalized vector).
         * @param angle The angle of rotation in radians.
         */
        fun fromAxisAngle(axis: FloatArray, angle: Float): Quaternion {
            val s = sin(angle / 2)
            return Quaternion(axis[0] * s, axis[1] * s, axis[2] * s, cos(angle / 2))
        }
    }
}