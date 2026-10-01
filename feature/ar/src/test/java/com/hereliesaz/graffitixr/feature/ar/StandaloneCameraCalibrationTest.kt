package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.graffitixr.common.sensor.CameraIntrinsics
import org.junit.Assert.assertEquals
import org.junit.Test

class StandaloneCameraCalibrationTest {

    @Test
    fun cropMovesPrincipalPointButKeepsFocalLength() {
        val raw = CameraIntrinsics(
            fx = 1000f,
            fy = 900f,
            cx = 960f,
            cy = 540f,
            width = 1920,
            height = 1080,
        )

        val cropped = cropCameraIntrinsics(
            intrinsics = raw,
            cropLeft = 240,
            cropTop = 90,
            cropWidth = 1440,
            cropHeight = 900,
        )

        assertEquals(1000f, cropped.fx, 0.0001f)
        assertEquals(900f, cropped.fy, 0.0001f)
        assertEquals(720f, cropped.cx, 0.0001f)
        assertEquals(450f, cropped.cy, 0.0001f)
        assertEquals(1440, cropped.width)
        assertEquals(900, cropped.height)
    }
}
