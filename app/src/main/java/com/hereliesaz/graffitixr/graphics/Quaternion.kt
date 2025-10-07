package com.hereliesaz.graffitixr.graphics

import android.opengl.Matrix
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A data class representing a quaternion, used for 3D rotations.
 *
 * Quaternions are used to represent rotations in 3D space and are preferable to Euler angles
 * to avoid issues like gimbal lock. This class is [Parcelable] to allow it to be stored in
 * the `UiState` and survive process death.
 *
 * @property x The x component of the quaternion.
 * @property y The y component of the quaternion.
 * @property z The z component of the quaternion.
 * @property w The w component of the quaternion (scalar part).
 */
@Parcelize
data class Quaternion(val x: Float, val y: Float, val z: Float, val w: Float) : Parcelable {

    /**
     * Multiplies this quaternion by another quaternion.
     *
     * @param other The other quaternion to multiply by.
     * @return The resulting quaternion.
     */
    operator fun times(other: Quaternion): Quaternion {
        val newX = w * other.x + x * other.w + y * other.z - z * other.y
        val newY = w * other.y - x * other.z + y * other.w + z * other.x
        val newZ = w * other.z + x * other.y - y * other.x + z * other.w
        val newW = w * other.w - x * other.x - y * other.y - z * other.z
        return Quaternion(newX, newY, newZ, newW)
    }

    /**
     * Converts the quaternion to a 4x4 rotation matrix.
     *
     * @return A 16-element float array representing the rotation matrix in column-major order,
     * suitable for use with OpenGL.
     */
    fun toGlMatrix(): FloatArray {
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

        matrix[3] = 0f
        matrix[7] = 0f
        matrix[11] = 0f
        matrix[12] = 0f
        matrix[13] = 0f
        matrix[14] = 0f
        matrix[15] = 1f

        return matrix
    }

    companion object {
        /**
         * Creates an identity quaternion.
         *
         * @return An identity quaternion (0, 0, 0, 1).
         */
        fun identity() = Quaternion(0f, 0f, 0f, 1f)

        /**
         * Creates a quaternion from an axis and an angle.
         *
         * @param axis The axis of rotation (must be a normalized 3-element float array).
         * @param angle The angle of rotation in radians.
         * @return The resulting quaternion.
         */
        fun fromAxisAngle(axis: FloatArray, angle: Float): Quaternion {
            val s = kotlin.math.sin(angle / 2)
            return Quaternion(axis[0] * s, axis[1] * s, axis[2] * s, kotlin.math.cos(angle / 2))
        }
    }
}