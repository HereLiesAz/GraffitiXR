package com.hereliesaz.graffitixr.common.sensor

data class ImuSample(
    val gyro: Vec3,
    val accel: Vec3,
    val timestampNs: Long,
)
