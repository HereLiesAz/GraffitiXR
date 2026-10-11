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
    fun `monocular depth stays dormant only while ARCore depth is the source`() {
        assertFalse(isMonocularDepthWanted(true, arCoreOptIn = true, arCoreDepthOptIn = true, arCoreSupported = true))
        assertTrue(isMonocularDepthWanted(true, arCoreOptIn = false, arCoreDepthOptIn = true, arCoreSupported = true))
        assertTrue(isMonocularDepthWanted(true, arCoreOptIn = true, arCoreDepthOptIn = false, arCoreSupported = true))
        assertTrue(isMonocularDepthWanted(true, arCoreOptIn = true, arCoreDepthOptIn = true, arCoreSupported = false))
        assertFalse(isMonocularDepthWanted(false, arCoreOptIn = false, arCoreDepthOptIn = false, arCoreSupported = false))
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
}
