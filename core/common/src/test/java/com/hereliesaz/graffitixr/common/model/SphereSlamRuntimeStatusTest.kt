package com.hereliesaz.graffitixr.common.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SphereSlamRuntimeStatusTest {

    @Test
    fun `sidecar diagnostic names fresh observations explicitly`() {
        val status = SphereSlamRuntimeStatus(
            available = true,
            mode = SphereSlamRuntimeMode.ARCORE_SIDECAR,
            active = true,
            referenceReady = true,
            trackingData = true,
        )

        assertEquals(
            "SphereSLAM available=true mode=ARCORE_SIDECAR active=true " +
                "referenceReady=true observations=true",
            status.diagnosticLine(),
        )
    }

    @Test
    fun `standalone diagnostic names tracking explicitly`() {
        val status = SphereSlamRuntimeStatus(
            available = true,
            mode = SphereSlamRuntimeMode.STANDALONE,
            active = true,
            referenceReady = true,
            mappingData = true,
            trackingData = false,
        )

        assertEquals(
            "SphereSLAM available=true mode=STANDALONE active=true " +
                "referenceReady=true mapping=true tracking=false",
            status.diagnosticLine(),
        )
    }

    @Test
    fun `unavailable diagnostic cannot masquerade as active tracking`() {
        val status = SphereSlamRuntimeStatus(
            available = false,
            mode = SphereSlamRuntimeMode.UNAVAILABLE,
        )

        assertEquals(
            "SphereSLAM available=false mode=UNAVAILABLE active=false referenceReady=false",
            status.diagnosticLine(),
        )
    }
}
