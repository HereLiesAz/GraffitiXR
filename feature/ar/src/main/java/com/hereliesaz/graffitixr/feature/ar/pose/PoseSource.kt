package com.hereliesaz.graffitixr.feature.ar.pose

/**
 * The seam between "what produces the camera pose" and "what consumes it" (the native SLAM map,
 * relocalization, paint-mark tracking — none of which care where the pose came from, only that it is
 * a correctly scaled 6-DoF view).
 *
 * Today the only implementation is [ArCorePoseSource], which wraps ARCore's visual-inertial odometry.
 * The reason this interface exists is the devices ARCore cannot reach (no Google Play Services for AR,
 * uncertified OEMs): a future `NativePoseSource` — an on-device VIO running on raw CameraX frames plus
 * the IMU (e.g. an embedded ORB-SLAM3 / OpenVINS, or a commercial engine) — would implement this same
 * contract and feed the identical consumer, so the SLAM pipeline above the seam is untouched.
 *
 * ## The contract a producer must satisfy
 *
 * [sample] fills two caller-owned length-16 arrays and returns per-frame metadata:
 *  - **viewMatrix** — column-major 4x4 world→view (OpenGL convention), exactly what
 *    `ARCore Camera.getViewMatrix` returns. This is what `SlamManager.updateCamera` integrates.
 *  - **projMatrix** — column-major 4x4 OpenGL perspective projection for the given near/far, exactly
 *    what `ARCore Camera.getProjectionMatrix` returns.
 *  - **[PoseMeta.timestampNs]** — the frame's monotonic capture timestamp in nanoseconds.
 *  - **[PoseMeta.isTracking]** — whether the pose this cycle is a valid, tracked pose (vs. lost /
 *    initializing). Consumers gate map integration and re-arm relocalization on this.
 *
 * Implementations MUST fill the arrays in place and MUST NOT allocate per call — [sample] runs on the
 * GL render thread every vsync, where per-frame allocation shows up as overlay stutter against the
 * wall.
 */
interface PoseSource {
    /**
     * Fill [viewMatrix] and [projMatrix] (both length 16, column-major, GL convention) with this
     * cycle's camera pose and projection, using [nearM]/[farM] as the projection clip planes in
     * metres. Returns the sample's [PoseMeta], or `null` when no pose is available this cycle (the
     * caller then skips pose-dependent work for the frame).
     */
    fun sample(viewMatrix: FloatArray, projMatrix: FloatArray, nearM: Float, farM: Float): PoseMeta?
}

/** Per-frame pose metadata that accompanies the matrices [PoseSource.sample] fills. */
data class PoseMeta(
    /** The frame's monotonic capture timestamp, nanoseconds. */
    val timestampNs: Long,
    /** True when this cycle's pose is valid and tracked (not lost or still initializing). */
    val isTracking: Boolean,
)
