package com.hereliesaz.graffitixr.common.sensor

import java.nio.ByteBuffer

data class CameraFrame(
    val pixels: ByteBuffer,
    val format: PixelFormat,
    val width: Int,
    val height: Int,
    val timestampNs: Long,
)
