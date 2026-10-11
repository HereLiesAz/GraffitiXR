package com.hereliesaz.graffitixr.feature.ar

import com.google.ar.core.CameraConfig
import com.hereliesaz.graffitixr.feature.ar.rendering.isSphereSlamSidecarActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure decisions behind the ARCore audit fixes. */
class ArCoreFixPoliciesTest {

    @Test
    fun `autosave compares the wall map with its own saved count`() {
        // Unchanged map, paint and progress: nothing to save (used to fire every 30 s because the live
        // ARCore cloud was compared with the saved wall-map count).
        assertFalse(shouldAutosaveWallMap(1_000, 1_000, 3, 3, 0.4f, 0.4f))
        // Wall map grew by the threshold.
        assertTrue(
            shouldAutosaveWallMap(1_000 + ArViewModel.AUTOSAVE_POINT_DELTA, 1_000, 3, 3, 0.4f, 0.4f),
        )
        // An empty map never triggers on its own.
        assertFalse(shouldAutosaveWallMap(0, 0, 0, 0, 0f, 0f))
        // Paint and progress still trigger independently.
        assertTrue(shouldAutosaveWallMap(0, 0, ArViewModel.AUTOSAVE_PAINT_DELTA, 0, 0f, 0f))
        assertTrue(shouldAutosaveWallMap(0, 0, 0, 0, ArViewModel.AUTOSAVE_PROGRESS_DELTA, 0f))
    }

    @Test
    fun `a probe that could not open the camera persists no verdict`() {
        assertEquals(1, StereoProbeService.persistedCapabilityFor(StereoProbeService.RESULT_CAPABLE))
        assertEquals(0, StereoProbeService.persistedCapabilityFor(StereoProbeService.RESULT_INCAPABLE))
        assertNull(StereoProbeService.persistedCapabilityFor(StereoProbeService.RESULT_CAMERA_UNAVAILABLE))
    }

    @Test
    fun `only an active REQUIRE_AND_USE config counts as the dual-lens stream`() {
        assertTrue(StereoProbeService.isDualLensStreamInForce(CameraConfig.StereoCameraUsage.REQUIRE_AND_USE))
        assertFalse(StereoProbeService.isDualLensStreamInForce(CameraConfig.StereoCameraUsage.DO_NOT_USE))
        assertFalse(StereoProbeService.isDualLensStreamInForce(null))
    }

    @Test
    fun `sidecar is reported active only while it is fed`() {
        assertTrue(isSphereSlamSidecarActive(available = true, referenceReady = true, fusionEnabled = true))
        // Fusion off: the renderer submits no KPM frames, so it is not active.
        assertFalse(isSphereSlamSidecarActive(available = true, referenceReady = true, fusionEnabled = false))
        assertFalse(isSphereSlamSidecarActive(available = true, referenceReady = false, fusionEnabled = true))
        assertFalse(isSphereSlamSidecarActive(available = false, referenceReady = true, fusionEnabled = true))
    }

    @Test
    fun `dead-camera clock runs only while a session is attached`() {
        // No session yet (probe / camera gate): the clock keeps restarting, so it can never expire.
        assertEquals(5_000L, ArCameraHealth.watchStartMs(currentStartMs = 0L, nowMs = 5_000L, sessionAttached = false))
        // Session attached: the start is kept and elapsed time accumulates.
        assertEquals(5_000L, ArCameraHealth.watchStartMs(currentStartMs = 5_000L, nowMs = 16_000L, sessionAttached = true))
        assertTrue(ArCameraHealth.isCameraDead(16_000L - 5_000L, lastFrameTimestampNs = 0L))
    }
}
