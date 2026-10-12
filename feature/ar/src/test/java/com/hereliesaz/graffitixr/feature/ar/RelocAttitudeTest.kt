package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.sphereslam.attitude.DeviceCameraRotation
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.math.sqrt

/** [RelocAttitude]: the earth ← OpenCV camera rotation the relocalizer turns by. */
class RelocAttitudeTest {

    private val h = (sqrt(0.5)).toFloat()

    @Test
    fun `a phone flat on its back looks straight down`() {
        // Identity: device x east, y north, z up; the rear camera looks along −z.
        val m = RelocAttitude.earthFromCvCamera(floatArrayOf(0f, 0f, 0f, 1f), DeviceCameraRotation.cameraFromDeviceForDisplayFrame(0))
        // Image right = east, image down = south, forward = down.
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, -1f), m, 1e-6f)
    }

    @Test
    fun `a portrait phone held up facing north looks north, upright`() {
        // 90° about east: device y → up, z → south (the screen faces the artist, the camera north).
        val m = RelocAttitude.earthFromCvCamera(floatArrayOf(h, 0f, 0f, h), DeviceCameraRotation.cameraFromDeviceForDisplayFrame(0))
        // Columns: image right = east, image down = earth down, forward = north.
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, -1f, 0f), m, 1e-6f)
    }

    @Test
    fun `turned to landscape, the displayed image is still upright`() {
        // The same phone turned 90° counterclockwise (display ROTATION_90): device x now points up.
        // Quaternion: 90° about east, then −90° about north (the camera axis): device x turns up.
        val q = mul(floatArrayOf(0f, -h, 0f, h), floatArrayOf(h, 0f, 0f, h))
        val m = RelocAttitude.earthFromCvCamera(q, DeviceCameraRotation.cameraFromDeviceForDisplayFrame(90))
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, -1f, 0f), m, 1e-5f)
    }

    /** Hamilton product a·b of [x, y, z, w] quaternions. */
    private fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val (ax, ay, az, aw) = a.toList(); val (bx, by, bz, bw) = b.toList()
        return floatArrayOf(
            aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
            aw * bw - ax * bx - ay * by - az * bz,
        )
    }
}
