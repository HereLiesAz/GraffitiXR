package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.graffitixr.common.model.CameraTargetFps
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StandaloneCameraTuningTest {

    private fun ranges(vararg r: Pair<Int, Int>) = r.map { intArrayOf(it.first, it.second) }

    private val typical = ranges(15 to 15, 7 to 30, 15 to 30, 30 to 30, 15 to 60, 60 to 60)

    @Test
    fun `30 pins a fixed 30 range`() {
        assertArrayEquals(
            intArrayOf(30, 30),
            StandaloneCameraTuning.chooseAeTargetFpsRange(typical, CameraTargetFps.FPS_30),
        )
    }

    @Test
    fun `device default leaves AE on the device range`() {
        assertNull(StandaloneCameraTuning.chooseAeTargetFpsRange(typical, CameraTargetFps.DEVICE_DEFAULT))
    }

    @Test
    fun `60 and device max pick the highest fixed range`() {
        assertArrayEquals(
            intArrayOf(60, 60),
            StandaloneCameraTuning.chooseAeTargetFpsRange(typical, CameraTargetFps.FPS_60),
        )
        assertArrayEquals(
            intArrayOf(60, 60),
            StandaloneCameraTuning.chooseAeTargetFpsRange(typical, CameraTargetFps.DEVICE_MAX),
        )
    }

    @Test
    fun `without an exact fixed range the closest upper bound with the highest floor wins`() {
        assertArrayEquals(
            intArrayOf(24, 30),
            StandaloneCameraTuning.chooseAeTargetFpsRange(
                ranges(7 to 30, 24 to 30, 15 to 15),
                CameraTargetFps.FPS_30,
            ),
        )
        // 60 unsupported: falls back to the closest (30).
        assertArrayEquals(
            intArrayOf(30, 30),
            StandaloneCameraTuning.chooseAeTargetFpsRange(
                ranges(15 to 30, 30 to 30),
                CameraTargetFps.FPS_60,
            ),
        )
        // Equidistant upper bounds: the lower one (less power, longer exposure headroom).
        assertArrayEquals(
            intArrayOf(25, 25),
            StandaloneCameraTuning.chooseAeTargetFpsRange(
                ranges(25 to 25, 35 to 35),
                CameraTargetFps.FPS_30,
            ),
        )
    }

    @Test
    fun `adaptive battery cap is honoured through the effective rate`() {
        val capped = effectiveCameraTargetFps(CameraTargetFps.DEVICE_MAX, adaptiveRateEnabled = true, batteryTier = 1)
        assertArrayEquals(intArrayOf(30, 30), StandaloneCameraTuning.chooseAeTargetFpsRange(typical, capped))
    }

    @Test
    fun `no or malformed ranges leave AE alone`() {
        assertNull(StandaloneCameraTuning.chooseAeTargetFpsRange(emptyList(), CameraTargetFps.FPS_30))
        assertNull(
            StandaloneCameraTuning.chooseAeTargetFpsRange(
                listOf(intArrayOf(30), intArrayOf(30, 15), intArrayOf(0, 0)),
                CameraTargetFps.FPS_30,
            ),
        )
    }
}
