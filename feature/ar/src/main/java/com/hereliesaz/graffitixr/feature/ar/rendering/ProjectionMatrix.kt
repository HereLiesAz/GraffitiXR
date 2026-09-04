// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/rendering/ProjectionMatrix.kt
package com.hereliesaz.graffitixr.feature.ar.rendering

import com.hereliesaz.graffitixr.common.sensor.CameraIntrinsics

/**
 * Builds an [OverlayRenderer]-ready OpenGL projection matrix from pinhole camera intrinsics — the
 * piece ARCore's `Camera.getProjectionMatrix()` supplies for free and CameraX doesn't, needed for
 * [com.hereliesaz.graffitixr.feature.ar.BridgedHomographyTracker]'s fallback pose to render through
 * the same [OverlayRenderer.draw] path AR mode uses.
 *
 * Unlike the device-IMU alignment in `GyroOrientationBridge` (asserted from platform guarantees,
 * not yet device-verified), this conversion is pure, closed-form math, derived and pin-tested here
 * (`ProjectionMatrixTest`) rather than copied from memory — worth spelling out once:
 *
 * A pinhole camera in OpenCV convention (+X right, +Y down, +Z forward) projects a point
 * `(Xc, Yc, Zc)` to pixel `u = fx·Xc/Zc + cx`, `v = fy·Yc/Zc + cy`. `HomographyTracker.h`'s pose
 * (and every other pose in this codebase) is in OpenGL eye space instead (+X right, +Y up, -Z
 * forward), related by the same `Y,Z` flip used throughout: `Xc = Xg`, `Yc = -Yg`, `Zc = -Zg`.
 * Substituting and converting pixel coordinates to normalized device coordinates
 * (`x_ndc = 2u/w - 1`, `y_ndc = 1 - 2v/h` — image-`v` grows downward, NDC-`y` grows upward) and
 * then to clip space (`Wclip = -Zg`, `x_ndc = Xclip/Wclip`) gives, row by row:
 * ```
 * Xclip = (2fx/w)·Xg                    + (1 - 2cx/w)·Zg
 * Yclip =              (2fy/h)·Yg       + (2cy/h - 1)·Zg
 * Zclip =                                -((f+n)/(f-n))·Zg - (2fn/(f-n))·Wg   // standard depth range
 * Wclip =                                -Zg
 * ```
 * — i.e. the intrinsics terms land only in the third *column* (`Zg`'s coefficients), which is
 * where [buildFrom] puts them.
 */
object ProjectionMatrix {

    /**
     * @param intrinsics pixel-space `fx, fy, cx, cy` and the image size they were measured
     *   against — NOT necessarily the render viewport's size; only their ratios matter, so a
     *   tracked-frame-resolution [CameraIntrinsics] is fine even if the GL surface is a different
     *   size, as long as it has the same aspect ratio.
     * @param near,far the GL near/far clip planes in the same (arbitrary, self-consistent) units
     *   [com.hereliesaz.graffitixr.feature.ar.HomographyArTracker]'s translations are expressed
     *   in. Defaults comfortably bracket a mural viewed from arm's length to across a room.
     * @return a column-major `FloatArray(16)`, usable directly as [OverlayRenderer.draw]'s `projMatrix`.
     */
    fun buildFrom(intrinsics: CameraIntrinsics, near: Float = 0.05f, far: Float = 50f): FloatArray {
        require(intrinsics.width > 0 && intrinsics.height > 0) { "intrinsics must carry a positive image size" }
        require(far > near && near > 0f) { "require 0 < near < far" }

        val w = intrinsics.width.toFloat()
        val h = intrinsics.height.toFloat()
        val m = FloatArray(16)
        // Column-major: index = col*4 + row.
        m[0] = 2f * intrinsics.fx / w
        m[5] = 2f * intrinsics.fy / h
        m[8] = 1f - 2f * intrinsics.cx / w
        m[9] = 2f * intrinsics.cy / h - 1f
        m[10] = -(far + near) / (far - near)
        m[11] = -1f
        m[14] = -2f * far * near / (far - near)
        return m
    }
}
