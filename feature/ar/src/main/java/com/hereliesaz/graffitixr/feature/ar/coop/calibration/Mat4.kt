package com.hereliesaz.graffitixr.feature.ar.coop.calibration

import com.hereliesaz.graffitixr.common.sensor.Vec3

data class Mat4(val values: FloatArray) {
    init { require(values.size == 16) { "Mat4 needs 16 floats" } }

    fun apply(v: Vec3): Vec3 {
        val x = values[0] * v.x + values[1] * v.y + values[2] * v.z + values[3]
        val y = values[4] * v.x + values[5] * v.y + values[6] * v.z + values[7]
        val z = values[8] * v.x + values[9] * v.y + values[10] * v.z + values[11]
        return Vec3(x, y, z)
    }

    fun approximateScale(): Float {
        val c0 = floatArrayOf(values[0], values[4], values[8])
        val c1 = floatArrayOf(values[1], values[5], values[9])
        val c2 = floatArrayOf(values[2], values[6], values[10])
        return listOf(c0, c1, c2).maxOf { col ->
            kotlin.math.sqrt(col[0] * col[0] + col[1] * col[1] + col[2] * col[2])
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Mat4 && values.contentEquals(other.values))

    override fun hashCode(): Int = values.contentHashCode()

    companion object {
        val IDENTITY: Mat4 = Mat4(
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f,
            )
        )

        internal fun fromRotationTranslation(rot: Mat3, t: Vec3): Mat4 = Mat4(
            floatArrayOf(
                rot.m00, rot.m01, rot.m02, t.x,
                rot.m10, rot.m11, rot.m12, t.y,
                rot.m20, rot.m21, rot.m22, t.z,
                0f, 0f, 0f, 1f,
            )
        )
    }
}
