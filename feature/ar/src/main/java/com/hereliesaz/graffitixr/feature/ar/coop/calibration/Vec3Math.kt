package com.hereliesaz.graffitixr.feature.ar.coop.calibration

import com.hereliesaz.graffitixr.common.sensor.Vec3
import kotlin.math.sqrt

internal operator fun Vec3.plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
internal operator fun Vec3.minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
internal operator fun Vec3.times(s: Float) = Vec3(x * s, y * s, z * s)
internal operator fun Vec3.div(s: Float) = Vec3(x / s, y / s, z / s)

internal fun Vec3.dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z
internal fun Vec3.cross(o: Vec3): Vec3 =
    Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
internal fun Vec3.length(): Float = sqrt(dot(this))
internal fun Vec3.normalized(): Vec3 = if (length() > 1e-9f) this / length() else Vec3.ZERO
