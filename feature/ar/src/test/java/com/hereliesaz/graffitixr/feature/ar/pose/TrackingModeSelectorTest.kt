package com.hereliesaz.graffitixr.feature.ar.pose

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingModeSelectorTest {

    @Test
    fun `ARCore plus KPM selects hybrid mode`() {
        assertEquals(
            TrackingMode.HYBRID_ARCORE_SPHERESLAM,
            TrackingModeSelector.select(
                TrackingCapabilities(
                    arCoreAvailable = true,
                    sphereSlamRelocalizationAvailable = true,
                    sphereSlamStandaloneAvailable = false,
                ),
            ),
        )
    }

    @Test
    fun `ARCore works alone when SphereSLAM relocalization is unavailable`() {
        assertEquals(
            TrackingMode.ARCORE_ONLY,
            TrackingModeSelector.select(
                TrackingCapabilities(
                    arCoreAvailable = true,
                    sphereSlamRelocalizationAvailable = false,
                    sphereSlamStandaloneAvailable = false,
                ),
            ),
        )
    }

    @Test
    fun `non ARCore device selects standalone only when full SphereSLAM backend is ready`() {
        assertEquals(
            TrackingMode.SPHERESLAM_STANDALONE,
            TrackingModeSelector.select(
                TrackingCapabilities(
                    arCoreAvailable = false,
                    sphereSlamRelocalizationAvailable = true,
                    sphereSlamStandaloneAvailable = true,
                ),
            ),
        )
    }

    @Test
    fun `KPM availability alone does not falsely enable standalone AR`() {
        assertEquals(
            TrackingMode.UNAVAILABLE,
            TrackingModeSelector.select(
                TrackingCapabilities(
                    arCoreAvailable = false,
                    sphereSlamRelocalizationAvailable = true,
                    sphereSlamStandaloneAvailable = false,
                ),
            ),
        )
    }
}
