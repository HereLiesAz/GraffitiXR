package com.hereliesaz.graffitixr.common.model

/**
 * Why the last relocalization attempt did not publish a pose, and how far it got.
 *
 * Every one of these failures used to be silent. The only published counters updated on SUCCESS, so a
 * relocalizer that had never locked once was indistinguishable from an idle one — all zeros, forever,
 * with no way to tell whether the fingerprint was missing, the frame had no texture, or PnP was
 * solving and falling one inlier short.
 *
 * Lives in `core:common` rather than next to `SlamManager` so both the AR UI state and the native
 * bridge can name the same type.
 */
data class RelocDiagnostics(
    val reject: RelocReject = RelocReject.UNKNOWN,
    /** Correspondences the last attempt built. PnP needs 8. */
    val matches: Int = 0,
    /** RANSAC inliers the last attempt found. Publishing needs 6; PoseFusion trusts a ratio ≥ 0.5. */
    val inliers: Int = 0,
    /**
     * Features detected in the live frame before any matching. Separates "this frame has no texture
     * to work with" (dark, blurred, blank wall) from "plenty of texture, none of it the registered
     * wall" (aimed somewhere else) — a low match count means opposite things in those two cases.
     */
    val detected: Int = 0,
    /**
     * Obliquity (degrees between the wall normal and the optical axis) measured by the plane-guided
     * rectification pass, or -1 when that pass wasn't eligible — no capture view stored, ARCore not
     * tracking, or too few fingerprint points. The pass only warps above 25°.
     */
    val obliquityDeg: Int = -1,
    /** Correspondences the rectification pass contributed on top of the plain and scaled passes. */
    val rectifiedCorrespondences: Int = 0,
) {
    /** Inlier ratio of the last attempt, or 0 when it produced no correspondences. */
    val inlierRatio: Float get() = if (matches > 0) inliers.toFloat() / matches else 0f
}

/**
 * Mirrors `MobileGS::RelocReject`, ordinal for ordinal — the native side reports an int. Ordered by
 * how early the gate sits in the pipeline, so a larger value means the attempt got further.
 */
enum class RelocReject {
    /** Published a pose. */
    OK,
    /** No wall fingerprint yet, or one carrying descriptors but no 3D points. */
    NO_FINGERPRINT,
    /** Relocalization switched off. */
    DISABLED,
    /** Nothing detected in the live frame, or a descriptor type that can't compare. */
    NO_FEATURES,
    /** Fewer than 8 correspondences survived the Lowe ratio test. */
    FEW_MATCHES,
    /** solvePnPRansac found no consistent pose. */
    PNP_FAILED,
    /** PnP solved but fewer than 6 inliers agreed. */
    FEW_INLIERS,
    /** The native side reported a code this build doesn't know. */
    UNKNOWN,
}
