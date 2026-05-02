package com.hereliesaz.graffitixr.feature.ar.sensor

import com.hereliesaz.graffitixr.common.sensor.CameraFrame
import com.hereliesaz.graffitixr.common.sensor.CameraIntrinsics
import com.hereliesaz.graffitixr.common.sensor.PhoneSensorSource
import com.hereliesaz.graffitixr.common.sensor.PixelFormat
import javax.inject.Inject

class PhoneCameraAdapter @Inject constructor(
    private val phoneSensorSource: PhoneSensorSource,
) {
    fun pumpFromExistingCallback(
        bytes: java.nio.ByteBuffer,
        format: PixelFormat,
        width: Int,
        height: Int,
        timestampNs: Long,
    ) {
        phoneSensorSource.pumpFrame(
            CameraFrame(pixels = bytes, format = format, width = width, height = height, timestampNs = timestampNs)
        )
    }

    fun setIntrinsics(intrinsics: CameraIntrinsics) {
        phoneSensorSource.setIntrinsics(intrinsics)
    }
}
