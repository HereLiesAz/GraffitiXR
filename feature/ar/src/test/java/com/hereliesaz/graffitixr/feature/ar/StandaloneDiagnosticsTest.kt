package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertTrue
import org.junit.Test

class StandaloneDiagnosticsTest {

    @Test
    fun `dump contains backend calibration KPM state and scale contract`() {
        val dump = standaloneDiagnosticDump(
            calibration = StandaloneCalibrationDiagnostics(
                cameraId = "0",
                timestampSource = StandaloneCameraTimestampSource.REALTIME,
                rawWidth = 1920,
                rawHeight = 1080,
                cropLeft = 0,
                cropTop = 0,
                cropWidth = 1920,
                cropHeight = 1080,
                displayWidth = 1080,
                displayHeight = 1920,
                rotationDegrees = 90,
                fx = 1000f,
                fy = 1001f,
                cx = 540f,
                cy = 960f,
            ),
            trackingState = StandaloneTrackingState.REACQUIRING,
            match = StandaloneMatchDiagnostics(
                pageNo = 3,
                inliers = 18,
                reprojectionError = 2.5f,
                observationAgeMs = 41f,
                matchDurationMs = 12f,
                source = SphereSlamStandalonePoseSource.KPM,
            ),
            physicallyMetric = true,
            referenceWidthUnits = 2.4f,
            failure = StandaloneFailureClassifier.event(
                StandaloneFailureReason.STALE_OBSERVATION,
                "ageMs=300",
            ),
        )

        for (expected in listOf(
            "backend=SPHERESLAM_STANDALONE",
            "state=REACQUIRING",
            "cameraId=0",
            "timestampSource=REALTIME",
            "displayFrame=1080x1920",
            "fx=1000.000",
            "pageId=3",
            "inliers=18",
            "reprojectionError=2.500",
            "observationAgeMs=41.000",
            "physicalScale=true",
            "referenceWidthUnits=2.400",
            "failure=STALE_OBSERVATION",
            "failureDetail=ageMs=300",
        )) {
            assertTrue("missing diagnostic field: $expected\n$dump", dump.contains(expected))
        }
    }

    @Test
    fun `dump explicitly labels unavailable metrics`() {
        val dump = standaloneDiagnosticDump(
            calibration = null,
            trackingState = StandaloneTrackingState.INITIALIZING,
            match = null,
            physicallyMetric = false,
            referenceWidthUnits = 1f,
            failure = null,
        )
        assertTrue(dump.contains("cameraId=unavailable"))
        assertTrue(dump.contains("pageId=unavailable"))
        assertTrue(dump.contains("observationAgeMs=unavailable"))
        assertTrue(dump.contains("physicalScale=false"))
        assertTrue(dump.contains("failure=none"))
    }
}
