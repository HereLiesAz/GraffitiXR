package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArCameraHealthTest {

    private val timeout = ArCameraHealth.DEAD_CAMERA_TIMEOUT_MS

    @Test
    fun `dead when no frame ever arrived past the timeout`() {
        assertTrue(ArCameraHealth.isCameraDead(elapsedMs = timeout, lastFrameTimestampNs = 0L))
        assertTrue(ArCameraHealth.isCameraDead(elapsedMs = timeout + 5_000L, lastFrameTimestampNs = 0L))
    }

    @Test
    fun `not dead before the timeout even with no frames`() {
        assertFalse(ArCameraHealth.isCameraDead(elapsedMs = timeout - 1L, lastFrameTimestampNs = 0L))
        assertFalse(ArCameraHealth.isCameraDead(elapsedMs = 0L, lastFrameTimestampNs = 0L))
    }

    @Test
    fun `never dead once frames are flowing, regardless of elapsed time`() {
        assertFalse(ArCameraHealth.isCameraDead(elapsedMs = timeout + 60_000L, lastFrameTimestampNs = 1L))
        assertFalse(ArCameraHealth.isCameraDead(elapsedMs = 0L, lastFrameTimestampNs = 123_456_789L))
    }

    @Test
    fun `respects a custom timeout`() {
        assertFalse(ArCameraHealth.isCameraDead(elapsedMs = 1_000L, lastFrameTimestampNs = 0L, timeoutMs = 2_000L))
        assertTrue(ArCameraHealth.isCameraDead(elapsedMs = 2_000L, lastFrameTimestampNs = 0L, timeoutMs = 2_000L))
    }
}
