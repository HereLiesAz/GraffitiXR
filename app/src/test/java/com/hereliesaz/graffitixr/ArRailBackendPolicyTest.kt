package com.hereliesaz.graffitixr

import com.hereliesaz.graffitixr.design.R as DesignR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArRailBackendPolicyTest {

    @Test
    fun `unresolved backend blocks backend-specific rail actions`() {
        val p = arRailBackendPolicy(
            arCoreAvailabilityResolved = false,
            arCoreAvailable = false,
            sphereSlamAvailabilityResolved = false,
            sphereSlamAvailable = false,
        )
        assertFalse(p.backendResolved)
        assertFalse(p.doodleRailEnabled)
        assertFalse(p.coopCalibrationAvailable)
        assertFalse(p.modePreviewExportAvailable)
        assertEquals(DesignR.string.rail_checking_ar_backend, p.exportDisabledReason)
    }

    @Test
    fun `ARCore backend enables legacy target and coop calibration paths`() {
        val p = arRailBackendPolicy(
            arCoreAvailabilityResolved = true,
            arCoreAvailable = true,
            sphereSlamAvailabilityResolved = false,
            sphereSlamAvailable = false,
        )
        assertTrue(p.backendResolved)
        assertFalse(p.standalone)
        assertTrue(p.doodleRailEnabled)
        assertTrue(p.coopCalibrationAvailable)
        assertTrue(p.modePreviewExportAvailable)
        assertNull(p.exportDisabledReason)
    }

    @Test
    fun `standalone backend enables Target and calibrated coop`() {
        val p = arRailBackendPolicy(
            arCoreAvailabilityResolved = true,
            arCoreAvailable = false,
            sphereSlamAvailabilityResolved = true,
            sphereSlamAvailable = true,
        )
        assertTrue(p.backendResolved)
        assertTrue(p.standalone)
        // Target is on the rail in standalone: it opens the shared capture flow.
        assertTrue(p.doodleRailEnabled)
        assertTrue(p.coopCalibrationAvailable)
        assertFalse(p.modePreviewExportAvailable)
        assertEquals(DesignR.string.export_standalone_ar_unavailable, p.exportDisabledReason)
    }

    @Test
    fun `standalone co-op stays disabled when KPM runtime is unavailable`() {
        val p = arRailBackendPolicy(
            arCoreAvailabilityResolved = true,
            arCoreAvailable = false,
            sphereSlamAvailabilityResolved = true,
            sphereSlamAvailable = false,
        )
        assertTrue(p.backendResolved)
        assertTrue(p.standalone)
        assertFalse(p.coopCalibrationAvailable)
    }

    @Test
    fun `standalone co-op waits for KPM capability resolution`() {
        val p = arRailBackendPolicy(
            arCoreAvailabilityResolved = true,
            arCoreAvailable = false,
            sphereSlamAvailabilityResolved = false,
            sphereSlamAvailable = false,
        )
        assertFalse(p.coopCalibrationAvailable)
    }
    @Test
    fun `standalone Host readiness uses page target not ARCore anchor or cloud`() {
        val p = arRailBackendPolicy(
            arCoreAvailabilityResolved = true,
            arCoreAvailable = false,
            sphereSlamAvailabilityResolved = true,
            sphereSlamAvailable = true,
        )
        assertTrue(
            coopHostReady(
                policy = p,
                arCoreAnchorEstablished = false,
                arCorePointCount = 0,
                standaloneReferenceRegistered = true,
            )
        )
        assertFalse(
            coopHostReady(
                policy = p,
                arCoreAnchorEstablished = true,
                arCorePointCount = 500,
                standaloneReferenceRegistered = false,
            )
        )
    }

    @Test
    fun `ARCore Host readiness still requires its anchor and mapped points`() {
        val p = arRailBackendPolicy(
            arCoreAvailabilityResolved = true,
            arCoreAvailable = true,
            sphereSlamAvailabilityResolved = false,
            sphereSlamAvailable = false,
        )
        assertTrue(coopHostReady(p, true, 1, false))
        assertFalse(coopHostReady(p, true, 0, true))
        assertFalse(coopHostReady(p, false, 100, true))
    }

}
