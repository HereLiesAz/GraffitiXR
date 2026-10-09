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

    /** MobileGS standalone object space is the fingerprint's local centred wall plane. */
    fun wallToMobileGsObject(point: Point3): FloatArray = point.toFloatArray()

    /**
     * Place a fingerprint-local wall plane into the already-running SphereSLAM photosphere map.
     *
     * Map convention is GL-style: +X right across the wall-facing arc, +Y up, and the centre viewing
     * direction points toward -Z. [azimuthDeltaDeg] is relative to the photosphere's wall heading,
     * clockwise-positive; [elevationDeg] is positive upward. [rangeUnits] is metres only when the
     * photosphere has metric range, otherwise it is a normalized map radius.
     *
     * Fingerprint local +Z points back toward the camera, matching +X image-right × +Y image-up.
     * The result is a column-major model matrix `map_from_fingerprint`.
     */
    fun mapFromFingerprint(
        azimuthDeltaDeg: Float,
        elevationDeg: Float,
        rangeUnits: Float,
    ): FloatArray {
        require(azimuthDeltaDeg.isFinite() && elevationDeg.isFinite())
        require(rangeUnits.isFinite() && rangeUnits > 0f)

        val az = Math.toRadians(azimuthDeltaDeg.toDouble())
        val el = Math.toRadians(elevationDeg.toDouble())
        val sinAz = kotlin.math.sin(az).toFloat()
        val cosAz = kotlin.math.cos(az).toFloat()
        val sinEl = kotlin.math.sin(el).toFloat()
        val cosEl = kotlin.math.cos(el).toFloat()

        // Camera-origin -> wall target direction in the photosphere map.
        val forward = floatArrayOf(
            sinAz * cosEl,
            sinEl,
            -cosAz * cosEl,
        )
        // Fingerprint +Z is the wall normal facing back toward the camera.
        val z = floatArrayOf(-forward[0], -forward[1], -forward[2])
        // Tangent direction for increasing elevation.
        val y = floatArrayOf(
            -sinAz * sinEl,
            cosEl,
            cosAz * sinEl,
        )
        // x = y × z, yielding image-right and a right-handed fingerprint basis.
        val x = floatArrayOf(
            y[1] * z[2] - y[2] * z[1],
            y[2] * z[0] - y[0] * z[2],
            y[0] * z[1] - y[1] * z[0],
        )

        return floatArrayOf(
            x[0], x[1], x[2], 0f,
            y[0], y[1], y[2], 0f,
            z[0], z[1], z[2], 0f,
            forward[0] * rangeUnits,
            forward[1] * rangeUnits,
            forward[2] * rangeUnits,
            1f,
        )
    }

    /**
     * Legacy centred-page identity retained only for old-project validation/migration tests.
     * New standalone precision targets MUST use an explicit photosphere map transform.
     */
    @Deprecated("Standalone fingerprints now require explicit map_from_fingerprint")
    fun fingerprintFromAnchor(): FloatArray = identity4()

    @Deprecated("Standalone fingerprints now require explicit map_from_fingerprint")
    fun anchorFromFingerprint(): FloatArray = identity4()

    /** Legacy identity check; do not use as the new photosphere-frame compatibility test. */
    @Deprecated("Standalone maps are no longer required to use fingerprint identity")
    fun isCenteredPageAnchor(anchor: FloatArray, epsilon: Float = 1e-5f): Boolean {
        if (anchor.size != 16 || !epsilon.isFinite() || epsilon < 0f) return false
        val identity = identity4()
        return anchor.indices.all { i ->
            anchor[i].isFinite() && kotlin.math.abs(anchor[i] - identity[i]) <= epsilon
        }
    }

    private fun identity4(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )
}
