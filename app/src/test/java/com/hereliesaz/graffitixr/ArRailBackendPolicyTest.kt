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
        assertNotNull(p.targetDisabledReason)
        assertNotNull(p.coopDisabledReason)
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
        assertNull(p.targetDisabledReason)
        assertNull(p.coopDisabledReason)
    }

    @Test
    fun `standalone backend redirects target capture and blocks uncalibrated coop`() {
        val p = arRailBackendPolicy(
            arCoreAvailabilityResolved = true,
            arCoreAvailable = false,
        )
        assertTrue(p.backendResolved)
        assertTrue(p.standalone)
        assertFalse(p.targetRailEnabled)
        assertFalse(p.coopCalibrationAvailable)
        assertTrue(p.targetDisabledReason!!.contains("Wall Target"))
        assertTrue(p.coopDisabledReason!!.contains("ARCore calibration"))
    }
}
