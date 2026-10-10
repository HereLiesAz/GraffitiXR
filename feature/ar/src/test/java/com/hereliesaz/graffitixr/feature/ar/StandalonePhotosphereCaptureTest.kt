package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandalonePhotosphereCaptureTest {

    private fun keyframe(
        headingDeg: Float,
        elevationDeg: Float,
        width: Int = 4,
        height: Int = 3,
        intrinsics: FloatArray = floatArrayOf(511.57f, 511.57f, 235.68f, 320.90f),
    ) = SphereSlamPhotosphereKeyframe(
        timestampNs = 1L,
        luma = ByteArray(width * height),
        width = width,
        height = height,
        intrinsics = intrinsics,
        headingDeg = headingDeg,
        elevationDeg = elevationDeg,
        orientationQuaternion = null,
        cameraFromMap = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f },
    )

    @Test
    fun noAttitudeNeverYieldsAView() {
        assertNull(
            StandalonePhotosphereCapture.view(
                attitude = null,
                currentTileKeyframe = keyframe(10f, 0f),
                latestKeyframe = keyframe(10f, 0f),
            )
        )
        assertNull(
            StandalonePhotosphereCapture.view(
                attitude = Float.NaN to 0f,
                currentTileKeyframe = keyframe(10f, 0f),
                latestKeyframe = null,
            )
        )
    }

    @Test
    fun currentTileKeyframeKeepsItsOwnDirection() {
        val view = StandalonePhotosphereCapture.view(
            attitude = 15f to 2f,
            currentTileKeyframe = keyframe(12f, 1f),
            latestKeyframe = keyframe(200f, -30f),
        )
        assertNotNull(view)
        assertEquals(12f, view!!.headingDeg, 0f)
        assertEquals(1f, view.elevationDeg, 0f)
        assertEquals(511.57f, view.fx, 0f)
    }

    @Test
    fun freshTileUsesCurrentAttitudeWithSessionIntrinsics() {
        val view = StandalonePhotosphereCapture.view(
            attitude = 95f to 5f,
            currentTileKeyframe = null,
            latestKeyframe = keyframe(10f, 0f, width = 6, height = 8),
        )
        assertNotNull(view)
        // Direction comes from where the camera points NOW, never from another tile's keyframe.
        assertEquals(95f, view!!.headingDeg, 0f)
        assertEquals(5f, view.elevationDeg, 0f)
        assertEquals(6, view.width)
        assertEquals(8, view.height)
        assertEquals(235.68f, view.cx, 0f)
        assertEquals(320.90f, view.cy, 0f)
    }

    @Test
    fun emptyPhotosphereOrBadIntrinsicsFailClosed() {
        assertNull(
            StandalonePhotosphereCapture.view(
                attitude = 0f to 0f,
                currentTileKeyframe = null,
                latestKeyframe = null,
            )
        )
        assertNull(
            StandalonePhotosphereCapture.view(
                attitude = 0f to 0f,
                currentTileKeyframe = keyframe(
                    0f,
                    0f,
                    intrinsics = floatArrayOf(0f, 500f, 2f, 1.5f),
                ),
                latestKeyframe = null,
            )
        )
    }

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
    fun everyBlockerHasAnArtistMessage() {
        StandalonePhotosphereCapture.Blocker.entries.forEach { blocker ->
            assertTrue(StandalonePhotosphereCapture.userMessage(blocker).isNotBlank())
        }
    }
}
