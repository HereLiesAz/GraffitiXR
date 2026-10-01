package com.hereliesaz.sphereslam

/**
 * Coordinate and scale conversions for calibrated artoolkitX KPM poses.
 *
 * KPM stores planar reference coordinates in millimetres:
 *
 * `x_mm = x_px / dpi * 25.4`
 *
 * and flips image Y so the reference plane is +X right / +Y up. Its 3x4 match pose is therefore a
 * camera-from-page transform in that millimetre coordinate system.
 *
 * The renderer wants a right-handed OpenGL world-to-view matrix (+X right, +Y up, -Z forward).
 * [pageToOpenGlViewMeters] reproduces artoolkitX's own `arglCameraViewRHf()` conversion and converts
 * translation from KPM millimetres to metres.
 */
object SphereSlamPoseMath {
    const val MILLIMETERS_TO_METERS: Float = 0.001f
    private const val MILLIMETERS_PER_INCH: Float = 25.4f

    /**
     * Choose KPM DPI so [referenceWidthPixels] spans exactly [referenceWidthMeters] in KPM space.
     *
     * This can be a real measured wall width, which makes the pose physically metric, or a
     * normalized logical width (for example 1 metre) when only stable visual registration is needed.
     * In the normalized case the word "metre" is merely the renderer's shared unit; distance readouts
     * must not be presented as physical measurements.
     */
    fun dpiForReferenceWidth(
        referenceWidthPixels: Int,
        referenceWidthMeters: Float,
    ): Float {
        require(referenceWidthPixels > 0) { "reference width must be positive" }
        require(referenceWidthMeters.isFinite() && referenceWidthMeters > 0f) {
            "reference width in metres must be finite and positive"
        }
        return referenceWidthPixels.toFloat() * (MILLIMETERS_PER_INCH / 1000f) /
            referenceWidthMeters
    }

    data class PageGeometry(
        val widthMeters: Float,
        val heightMeters: Float,
        val centerXmm: Float,
        val centerYmm: Float,
        val referenceDpi: Float,
    ) {
        init {
            require(widthMeters > 0f && heightMeters > 0f)
            require(centerXmm.isFinite() && centerYmm.isFinite())
            require(referenceDpi > 0f)
        }
    }

    /** Geometry KPM will assign to a reference image for the supplied DPI. */
    fun pageGeometry(
        widthPixels: Int,
        heightPixels: Int,
        referenceDpi: Float,
    ): PageGeometry {
        require(widthPixels > 0 && heightPixels > 0)
        require(referenceDpi.isFinite() && referenceDpi > 0f)
        val widthMm = widthPixels.toFloat() / referenceDpi * MILLIMETERS_PER_INCH
        val heightMm = heightPixels.toFloat() / referenceDpi * MILLIMETERS_PER_INCH
        return PageGeometry(
            widthMeters = widthMm * MILLIMETERS_TO_METERS,
            heightMeters = heightMm * MILLIMETERS_TO_METERS,
            centerXmm = widthMm * 0.5f,
            centerYmm = heightMm * 0.5f,
            referenceDpi = referenceDpi,
        )
    }

    /**
     * Convert artoolkitX's row-major camera-from-page 3x4 transform to a column-major OpenGL
     * world-to-view matrix in metres.
     *
     * KPM's page origin is at the lower-left of the reference image. GraffitiXR renders wall
     * artwork around its local origin, so [pageCenterXmm]/[pageCenterYmm] shift that lower-left KPM
     * frame to a centered wall frame before the handedness conversion.
     *
     * The sign/transpose layout below intentionally mirrors artoolkitX
     * `arglCameraViewRHf(para, modelview, scale)`:
     * - first camera row unchanged;
     * - second and third camera rows negated;
     * - 3x4 row-major input written as a column-major 4x4 matrix;
     * - translation scaled from millimetres to metres.
     */
    fun pageToOpenGlViewMeters(
        cameraFromPage3x4: FloatArray,
        pageCenterXmm: Float = 0f,
        pageCenterYmm: Float = 0f,
    ): FloatArray {
        require(cameraFromPage3x4.size == 12) { "KPM pose must contain 12 floats" }
        require(cameraFromPage3x4.all { it.isFinite() }) { "KPM pose must be finite" }
        require(pageCenterXmm.isFinite() && pageCenterYmm.isFinite())

        val p = cameraFromPage3x4

        // Re-express the same rigid transform around a centered page origin:
        // camera = R * (localCentered + pageCenter) + t.
        val tx = p[3] + p[0] * pageCenterXmm + p[1] * pageCenterYmm
        val ty = p[7] + p[4] * pageCenterXmm + p[5] * pageCenterYmm
        val tz = p[11] + p[8] * pageCenterXmm + p[9] * pageCenterYmm

        val out = FloatArray(16)
        // Row 0.
        out[0] = p[0]
        out[4] = p[1]
        out[8] = p[2]
        out[12] = tx * MILLIMETERS_TO_METERS

        // Rows 1 and 2 are flipped exactly as arglCameraViewRHf does when moving from the
        // camera/image convention to a right-handed OpenGL eye frame.
        out[1] = -p[4]
        out[5] = -p[5]
        out[9] = -p[6]
        out[13] = -ty * MILLIMETERS_TO_METERS

        out[2] = -p[8]
        out[6] = -p[9]
        out[10] = -p[10]
        out[14] = -tz * MILLIMETERS_TO_METERS

        out[15] = 1f
        return out
    }
}
