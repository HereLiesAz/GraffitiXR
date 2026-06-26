package com.hereliesaz.graffitixr.feature.ar

/**
 * Pure decision helpers for the AR camera-health watchdog. Kept Android-free so the timeout logic is
 * unit-testable without a device.
 */
object ArCameraHealth {

    /**
     * How long the AR session may run without ARCore ever delivering a real camera frame before the
     * camera is treated as dead. The renderer's own on-screen stall warning fires at ~8s of ts=0 on
     * this hardware; this is set a little past that so the UI-side abandon only triggers once the
     * in-engine watchdog has also given up.
     */
    const val DEAD_CAMERA_TIMEOUT_MS = 10_000L

    /**
     * True when an AR session that has been running for [elapsedMs] still hasn't received a single
     * camera frame, i.e. [lastFrameTimestampNs] is still 0. ARCore returns a frame timestamp of 0
     * until its first camera image lands, so a still-zero timestamp past [timeoutMs] means the camera
     * never started feeding the session (e.g. a HAL that drops the device under repeated
     * ERROR_CAMERA_DEVICE). Leaving such a session open lets ARCore's internal camera pipe keep
     * thrashing the dead device, which on some devices ends in an uncatchable teardown crash — so the
     * caller should abandon AR when this returns true. A non-zero timestamp means frames are flowing
     * and the session is healthy, regardless of elapsed time.
     */
    fun isCameraDead(
        elapsedMs: Long,
        lastFrameTimestampNs: Long,
        timeoutMs: Long = DEAD_CAMERA_TIMEOUT_MS,
    ): Boolean = lastFrameTimestampNs == 0L && elapsedMs >= timeoutMs
}
