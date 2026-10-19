package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app-owned half of the capture seam: which input is missing and the artist-facing message.
 * Choosing the capture view and placing the doodle are SphereSLAM's
 * (`PhotosphereKeyframeStore.view`, `PhotosphereFingerprintFrame.placement`) and tested there.
 */
class StandalonePhotosphereCaptureTest {

    @Test
    fun blockerNamesTheFirstMissingInput() {
        assertEquals(
            StandalonePhotosphereCapture.Blocker.ATTITUDE_SENSOR_MISSING,
            StandalonePhotosphereCapture.blocker(
                attitudeSensorAvailable = false,
                attitude = null,
                hasAnyKeyframe = false,
            ),
        )
        assertEquals(
            StandalonePhotosphereCapture.Blocker.ATTITUDE_NOT_READY,
            StandalonePhotosphereCapture.blocker(
                attitudeSensorAvailable = true,
                attitude = null,
                hasAnyKeyframe = true,
            ),
        )
        assertEquals(
            StandalonePhotosphereCapture.Blocker.PHOTOSPHERE_EMPTY,
            StandalonePhotosphereCapture.blocker(
                attitudeSensorAvailable = true,
                attitude = 0f to 0f,
                hasAnyKeyframe = false,
            ),
        )
        assertNull(
            StandalonePhotosphereCapture.blocker(
                attitudeSensorAvailable = true,
                attitude = 0f to 0f,
                hasAnyKeyframe = true,
            )
        )
    }

    @Test
    fun everyBlockerHasItsOwnArtistMessageKey() {
        val keys = StandalonePhotosphereCapture.Blocker.entries.map { StandalonePhotosphereCapture.userText(it).key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it.name.startsWith("PHOTOSPHERE_") })
    }
}
