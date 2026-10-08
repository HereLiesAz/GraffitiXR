package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandaloneAnalyzerStartupTest {

    @Test
    fun localStandaloneDoesNotStartBeforeReferenceExists() {
        assertFalse(
            shouldStartStandaloneAnalyzer(
                cameraIdPresent = true,
                atlasLoaded = true,
                peerOnlyTracking = false,
                localReferencePresent = false,
            )
        )
    }

    @Test
    fun localStandaloneStartsOnceReferenceExists() {
        assertTrue(
            shouldStartStandaloneAnalyzer(
                cameraIdPresent = true,
                atlasLoaded = true,
                peerOnlyTracking = false,
                localReferencePresent = true,
            )
        )
    }

    @Test
    fun peerTrackingDoesNotRequireLocalReference() {
        assertTrue(
            shouldStartStandaloneAnalyzer(
                cameraIdPresent = true,
                atlasLoaded = true,
                peerOnlyTracking = true,
                localReferencePresent = false,
            )
        )
    }
}
