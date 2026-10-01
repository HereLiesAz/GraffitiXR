package com.hereliesaz.graffitixr.feature.ar

internal data class StandaloneDesignHalfExtents(
    val halfWidth: Float,
    val halfHeight: Float,
)

/**
 * Aspect-fit a design inside the standalone wall page without touching the persisted user transform.
 *
 * The returned half-extents are the renderer's BASE quad size. Pan/scale/rotation remain entirely in
 * ModeAdjustment, so replacing a portrait design with a landscape one changes only the aspect-fit
 * base geometry and never resets the artist's adjustment.
 */
internal fun fitStandaloneDesignHalfExtents(
    pageWidthUnits: Float,
    pageHeightUnits: Float,
    designWidthPx: Int?,
    designHeightPx: Int?,
): StandaloneDesignHalfExtents? {
    if (
        !pageWidthUnits.isFinite() ||
        !pageHeightUnits.isFinite() ||
        pageWidthUnits <= 0f ||
        pageHeightUnits <= 0f
    ) {
        return null
    }

    if (
        designWidthPx == null ||
        designHeightPx == null ||
        designWidthPx <= 0 ||
        designHeightPx <= 0
    ) {
        return StandaloneDesignHalfExtents(
            halfWidth = pageWidthUnits * 0.5f,
            halfHeight = pageHeightUnits * 0.5f,
        )
    }

    val aspect = designWidthPx.toFloat() / designHeightPx.toFloat()
    var width = pageWidthUnits
    var height = width / aspect
    if (height > pageHeightUnits) {
        height = pageHeightUnits
        width = height * aspect
    }
    return StandaloneDesignHalfExtents(
        halfWidth = width * 0.5f,
        halfHeight = height * 0.5f,
    )
}


internal data class StandaloneDesignPlacement(
    /** Column-major fingerprint_from_design rigid transform. */
    val fingerprintFromDesign: FloatArray,
    /** Scale-included effective half-extents; scale is deliberately NOT in the matrix. */
    val halfWidth: Float,
    val halfHeight: Float,
)

/**
 * Build the exact rigid design placement consumed by MobileGS corroboration/self-grow.
 *
 * The standalone fingerprint frame IS the renderer wall frame, so the only rigid transform is the
 * user's in-plane pan + Z spin. GraffitiXR stores ModeAdjustment.rotation as screen-space CW+ while
 * the wall frame is right-handed CCW+, hence the sign flip. Scale belongs in the extents, matching
 * ArRenderer/FingerprintPartition; putting it into this matrix would corrupt Φ classification.
 *
 * rotationX/rotationY are intentionally absent: in GraffitiXR they are texture-content perspective
 * effects, not physical wall-plane pose, and the ARCore placement path likewise keeps them out of Φ.
 */
internal fun standaloneDesignPlacement(
    base: StandaloneDesignHalfExtents?,
    panX: Float,
    panY: Float,
    scale: Float,
    storedClockwiseRotationDeg: Float,
): StandaloneDesignPlacement? {
    val fit = base ?: return null
    if (
        !panX.isFinite() ||
        !panY.isFinite() ||
        !scale.isFinite() ||
        !storedClockwiseRotationDeg.isFinite()
    ) {
        return null
    }

    val safeScale = scale.coerceAtLeast(0.001f)
    val glDegrees = -storedClockwiseRotationDeg
    val rad = Math.toRadians(glDegrees.toDouble())
    val c = kotlin.math.cos(rad).toFloat()
    val s = kotlin.math.sin(rad).toFloat()

    // Column-major T * Rz. This is the same order HomographyOverlayRenderer applies before scale.
    val rigid = floatArrayOf(
        c,  s, 0f, 0f,
       -s,  c, 0f, 0f,
        0f, 0f, 1f, 0f,
        panX, panY, 0f, 1f,
    )
    return StandaloneDesignPlacement(
        fingerprintFromDesign = rigid,
        halfWidth = fit.halfWidth * safeScale,
        halfHeight = fit.halfHeight * safeScale,
    )
}
