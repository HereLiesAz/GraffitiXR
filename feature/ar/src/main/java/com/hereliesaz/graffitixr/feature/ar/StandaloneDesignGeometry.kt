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
