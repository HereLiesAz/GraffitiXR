package com.hereliesaz.graffitixr.feature.ar.anchor

/**
 * The display-rotation convention **at its origin** — the two transforms the capture path applies to
 * a frame before anything geometric touches it.
 *
 * These lived inline in `ArRenderer`'s capture block (intrinsics) and `ArViewModel.onTargetCaptured`
 * (bitmap), which made them untestable, and that was a real hole rather than a tidiness complaint:
 * Phase 0's whole correctness argument is that a sensor ray `d_s` becomes `R_z(+θ)·d_s` under this
 * pair of transforms, and **nothing in the test suite drove a pixel through the actual code**. The
 * derivation and the tests both rested on one person's reading of these lines, so an error in that
 * reading would have been mirrored into the tests and passed.
 *
 * Extracted here so `CaptureRotationTest` can close the loop end to end: project a known 3D point
 * with sensor intrinsics, rotate the pixel the way the bitmap rotation does, unproject with the
 * rotated intrinsics, and check the resulting ray against `R_z(+θ)` computed independently. If the
 * convention is wrong, that test fails — which no existing test could do.
 */
object CaptureRotation {

    /**
     * Rotate raw sensor intrinsics into display orientation.
     *
     * Byte-for-byte the transform `ArRenderer` performed inline: swap the focal lengths on the
     * quarter turns, and remap the principal point against the raw frame's dimensions.
     *
     * @param rawW / [rawH] the **unrotated** image dimensions (`CameraIntrinsics.imageDimensions`).
     * @return `[fx, fy, cx, cy]` for the rotated frame.
     */
    fun rotateIntrinsics(
        fx: Float, fy: Float, cx: Float, cy: Float,
        rawW: Float, rawH: Float,
        rotationDeg: Int,
    ): FloatArray {
        var rfx = fx; var rfy = fy; var rcx = cx; var rcy = cy
        when (((rotationDeg % 360) + 360) % 360) {
            90 -> {
                val t = rfx; rfx = rfy; rfy = t
                val tcx = rcx; rcx = rawH - rcy; rcy = tcx
            }
            180 -> {
                rcx = rawW - rcx
                rcy = rawH - rcy
            }
            270 -> {
                val t = rfx; rfx = rfy; rfy = t
                val tcx = rcx; rcx = rcy; rcy = rawW - tcx
            }
        }
        return floatArrayOf(rfx, rfy, rcx, rcy)
    }

    /**
     * Where a raw sensor pixel `(u, v)` lands once the bitmap has been rotated by [rotationDeg].
     *
     * Mirrors `android.graphics.Matrix.postRotate(deg)` followed by `Bitmap.createBitmap`, which
     * rotates clockwise in the image's y-down coordinates and then re-offsets the result so the
     * rotated bitmap starts at the origin. For 90° that is `(u, v) -> (rawH - v, u)`.
     *
     * Expressed in continuous coordinates, matching how the principal point is remapped above — the
     * half-pixel question does not arise because both this and [rotateIntrinsics] use the same
     * convention, and the ray they produce depends only on their difference.
     */
    fun rotatePixel(u: Float, v: Float, rawW: Float, rawH: Float, rotationDeg: Int): FloatArray =
        when (((rotationDeg % 360) + 360) % 360) {
            90 -> floatArrayOf(rawH - v, u)
            180 -> floatArrayOf(rawW - u, rawH - v)
            270 -> floatArrayOf(v, rawW - u)
            else -> floatArrayOf(u, v)
        }
}
