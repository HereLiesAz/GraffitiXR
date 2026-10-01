package com.hereliesaz.graffitixr.feature.ar.anchor

import com.hereliesaz.sphereslam.SphereSlamPoseMath

/**
 * Durable coordinate contract for the ARCore-independent wall fingerprint.
 *
 * There is deliberately only one standalone wall/object frame:
 *
 * - origin: centre of the rectified SphereSLAM reference page;
 * - +X: image/right along the wall;
 * - +Y: image/up along the wall;
 * - +Z: wall normal completing a right-handed local frame;
 * - units: the same units as [SphereSlamPoseMath.PageGeometry] (physical metres when the reference
 *   has a measured width; normalized renderer units otherwise).
 *
 * artoolkitX/KPM stores its page origin at the reference image's lower-left in millimetres. The
 * standalone renderer already subtracts the page centre before converting the KPM camera pose.
 * MobileGS PnP object points use this same centred frame rather than inventing a second standalone
 * "world".
 *
 * OpenCV's camera frame (+X right, +Y down, +Z forward) is a property of the solved CAMERA pose,
 * not a requirement that the arbitrary PnP OBJECT frame use +Y down. Keeping the object frame
 * identical to the renderer/KPM wall frame is therefore valid and makes design/fingerprint
 * composition explicit.
 */
object StandaloneFingerprintFrame {
    private const val MILLIMETERS_TO_METERS = SphereSlamPoseMath.MILLIMETERS_TO_METERS
    private const val MILLIMETERS_PER_INCH = 25.4f

    data class Point3(
        val x: Float,
        val y: Float,
        val z: Float = 0f,
    ) {
        init {
            require(x.isFinite() && y.isFinite() && z.isFinite())
        }

        fun toFloatArray(): FloatArray = floatArrayOf(x, y, z)
    }

    /**
     * Convert a feature coordinate from the rectified reference bitmap to the centred wall frame.
     *
     * The +0.5 offsets intentionally reproduce artoolkitX's kpmGenRefDataSet full-size mapping:
     *
     * x_mm = (u + 0.5) / dpi * 25.4
     * y_mm = ((height - 0.5) - v) / dpi * 25.4
     *
     * before subtracting the KPM page centre. This keeps MobileGS object points and KPM's own page
     * coordinates coincident at sub-pixel precision instead of merely agreeing on the page extents.
     */
    fun referencePixelToWall(
        u: Float,
        v: Float,
        widthPixels: Int,
        heightPixels: Int,
        geometry: SphereSlamPoseMath.PageGeometry,
    ): Point3 {
        require(widthPixels > 0 && heightPixels > 0)
        require(u.isFinite() && v.isFinite())
        require(u >= -0.5f && u <= widthPixels - 0.5f) { "u lies outside the reference image" }
        require(v >= -0.5f && v <= heightPixels - 0.5f) { "v lies outside the reference image" }

        val dpi = geometry.referenceDpi
        val xMm = (u + 0.5f) / dpi * MILLIMETERS_PER_INCH
        val yMm = ((heightPixels - 0.5f) - v) / dpi * MILLIMETERS_PER_INCH
        return kpmPageMillimetersToWall(xMm, yMm, geometry)
    }

    /**
     * KPM lower-left page millimetres -> centred standalone wall/MobileGS object coordinates.
     */
    fun kpmPageMillimetersToWall(
        xMm: Float,
        yMm: Float,
        geometry: SphereSlamPoseMath.PageGeometry,
    ): Point3 {
        require(xMm.isFinite() && yMm.isFinite())
        return Point3(
            x = (xMm - geometry.centerXmm) * MILLIMETERS_TO_METERS,
            y = (yMm - geometry.centerYmm) * MILLIMETERS_TO_METERS,
            z = 0f,
        )
    }

    /** MobileGS standalone object space is intentionally identical to the centred wall frame. */
    fun wallToMobileGsObject(point: Point3): FloatArray = point.toFloatArray()

    /**
     * Standalone fingerprint anchor == standalone wall frame.
     *
     * Existing ARCore fingerprints need a world-space anchor model because their object points live
     * in a capture-camera frame. Standalone page points already live in the durable wall/anchor
     * frame, so the object-from-anchor transform is identity.
     */
    fun fingerprintFromAnchor(): FloatArray = identity4()

    /** Inverse of [fingerprintFromAnchor]; kept explicit at call sites for frame readability. */
    fun anchorFromFingerprint(): FloatArray = identity4()

    private fun identity4(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )
}
