package com.hereliesaz.graffitixr.feature.ar.pose

import com.google.ar.core.Frame
import com.google.ar.core.TrackingState

/**
 * [PoseSource] backed by ARCore's visual-inertial odometry.
 *
 * ARCore delivers a new [Frame] on every `Session.update()`, on the GL render thread. The renderer
 * hands each frame to [bind] and then calls [sample]; keeping the frame in a field (rather than making
 * it a `sample` parameter) is what lets the SLAM consumer talk to the mode-agnostic [PoseSource]
 * interface — a `NativePoseSource` has no ARCore frame to pass.
 *
 * Not thread-safe by design: [bind] and [sample] are both called from the single GL thread within one
 * `onDrawFrame`, the only place an ARCore frame is valid.
 */
class ArCorePoseSource : PoseSource {

    private var frame: Frame? = null

    /** Bind the current cycle's ARCore frame. Called once per `onDrawFrame`, before [sample]. */
    fun bind(frame: Frame) {
        this.frame = frame
    }

    /**
     * Fills [viewMatrix]/[projMatrix] from the bound frame's camera — the same
     * `getViewMatrix` / `getProjectionMatrix` calls the renderer made inline before this seam existed,
     * so the values handed to the SLAM map are byte-for-byte identical. Returns null when no frame is
     * bound.
     */
    override fun sample(
        viewMatrix: FloatArray,
        projMatrix: FloatArray,
        nearM: Float,
        farM: Float,
    ): PoseMeta? {
        val camera = frame?.camera ?: return null
        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projMatrix, 0, nearM, farM)
        return PoseMeta(
            timestampNs = frame?.timestamp ?: return null,
            isTracking = camera.trackingState == TrackingState.TRACKING,
        )
    }
}
