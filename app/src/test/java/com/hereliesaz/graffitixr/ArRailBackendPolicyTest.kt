package com.hereliesaz.graffitixr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArRailBackendPolicyTest {

    @Test
    fun `unresolved backend blocks backend-specific rail actions`() {
        val p = arRailBackendPolicy(
            arCoreAvailabilityResolved = false,
            arCoreAvailable = false,
        )
        assertFalse(p.backendResolved)
        assertFalse(p.targetRailEnabled)
        assertFalse(p.coopCalibrationAvailable)
        assertFalse(p.modePreviewExportAvailable)
        assertNotNull(p.targetDisabledReason)
        assertNotNull(p.coopDisabledReason)
        assertNotNull(p.exportDisabledReason)
    }

    @Test
    fun `ARCore backend enables legacy target and coop calibration paths`() {
        val p = arRailBackendPolicy(
            arCoreAvailabilityResolved = true,
            arCoreAvailable = true,
        )
        assertTrue(p.backendResolved)
        assertFalse(p.standalone)
        assertTrue(p.targetRailEnabled)
        assertTrue(p.coopCalibrationAvailable)
        assertTrue(p.modePreviewExportAvailable)
        assertNull(p.targetDisabledReason)
        assertNull(p.coopDisabledReason)
        assertNull(p.exportDisabledReason)
    }

    @Test
    fun `standalone backend redirects target capture and enables calibrated coop`() {
        val p = arRailBackendPolicy(
            arCoreAvailabilityResolved = true,
            arCoreAvailable = false,
        )
        assertTrue(p.backendResolved)
        assertTrue(p.standalone)
        assertFalse(p.targetRailEnabled)
        assertTrue(p.coopCalibrationAvailable)
        assertFalse(p.modePreviewExportAvailable)
        assertTrue(p.targetDisabledReason!!.contains("Wall Target"))
        assertNull(p.coopDisabledReason)
        assertTrue(p.exportDisabledReason!!.contains("not implemented"))
    }
}
