package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.graffitixr.common.model.CameraTargetFps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSettingsPolicyTest {

    @Test
    fun `ARCore backend needs both a supported install and the opt-in`() {
        assertTrue(isArCoreBackendSelected(ArAvailabilityChecker.Result.Supported, arCoreOptIn = true))
        assertFalse(isArCoreBackendSelected(ArAvailabilityChecker.Result.Supported, arCoreOptIn = false))
        assertFalse(isArCoreBackendSelected(ArAvailabilityChecker.Result.Unsupported, arCoreOptIn = true))
        assertFalse(
            isArCoreBackendSelected(ArAvailabilityChecker.Result.NeedsInstallOrUpdate, arCoreOptIn = true),
        )
    }

    @Test
    fun `monocular depth loads only for the standalone backend and only when opted in`() {
        // ARCore backend: no monocular consumer, whatever ARCore depth is set to.
        assertFalse(isMonocularDepthWanted(true, arCoreBackendSelected = true))
        assertTrue(isMonocularDepthWanted(true, arCoreBackendSelected = false))
        assertFalse(isMonocularDepthWanted(false, arCoreBackendSelected = false))
        assertFalse(isMonocularDepthWanted(false, arCoreBackendSelected = true))
    }

    @Test
    fun `battery pressure caps the camera to 30 only with adaptive rate on`() {
        assertEquals(CameraTargetFps.FPS_60, effectiveCameraTargetFps(CameraTargetFps.FPS_60, true, batteryTier = 0))
        assertEquals(CameraTargetFps.FPS_30, effectiveCameraTargetFps(CameraTargetFps.FPS_60, true, batteryTier = 1))
        assertEquals(CameraTargetFps.FPS_30, effectiveCameraTargetFps(CameraTargetFps.DEVICE_MAX, true, batteryTier = 2))
        assertEquals(CameraTargetFps.DEVICE_MAX, effectiveCameraTargetFps(CameraTargetFps.DEVICE_MAX, false, batteryTier = 2))
        assertEquals(
            CameraTargetFps.DEVICE_DEFAULT,
            effectiveCameraTargetFps(CameraTargetFps.DEVICE_DEFAULT, false, batteryTier = 0),
        )
    }

    @Test
    fun `standalone keeps device default unpinned under battery pressure`() {
        for (tier in 0..3) {
            val fps = standaloneEffectiveCameraTargetFps(CameraTargetFps.DEVICE_DEFAULT, true, batteryTier = tier)
            assertEquals(CameraTargetFps.DEVICE_DEFAULT, fps)
            // ...so the AE range is left to the device, never pinned to [30, 30].
            assertEquals(
                null,
                StandaloneCameraTuning.chooseAeTargetFpsRange(
                    listOf(intArrayOf(15, 30), intArrayOf(30, 30), intArrayOf(60, 60)),
                    fps,
                ),
            )
        }
    }

    @Test
    fun `standalone battery cap only lowers explicit higher rates`() {
        assertEquals(CameraTargetFps.FPS_30, standaloneEffectiveCameraTargetFps(CameraTargetFps.FPS_60, true, batteryTier = 1))
        assertEquals(CameraTargetFps.FPS_30, standaloneEffectiveCameraTargetFps(CameraTargetFps.DEVICE_MAX, true, batteryTier = 2))
        assertEquals(CameraTargetFps.FPS_30, standaloneEffectiveCameraTargetFps(CameraTargetFps.FPS_30, true, batteryTier = 2))
        assertEquals(CameraTargetFps.FPS_60, standaloneEffectiveCameraTargetFps(CameraTargetFps.FPS_60, true, batteryTier = 0))
        assertEquals(CameraTargetFps.FPS_60, standaloneEffectiveCameraTargetFps(CameraTargetFps.FPS_60, false, batteryTier = 3))
    }

    @Test
    fun `ARCore cap is unchanged - device default still caps to 30 under pressure`() {
        assertEquals(CameraTargetFps.FPS_30, effectiveCameraTargetFps(CameraTargetFps.DEVICE_DEFAULT, true, batteryTier = 1))
    }
}
