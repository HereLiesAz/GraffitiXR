package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandaloneAnalyzerStartupTest {

    @Test
    fun localStandaloneStartsBeforeFingerprintExists() {
        assertTrue(
            shouldStartStandaloneAnalyzer(
                cameraIdPresent = true,
                atlasLoaded = true,
            )
        )
    }

    @Test
    fun standaloneWaitsOnlyForCameraAndAtlasState() {
        assertFalse(
            shouldStartStandaloneAnalyzer(
                cameraIdPresent = false,
                atlasLoaded = true,
            )
        )
        assertFalse(
            shouldStartStandaloneAnalyzer(
                cameraIdPresent = true,
                atlasLoaded = false,
            )
        )
    }
}
