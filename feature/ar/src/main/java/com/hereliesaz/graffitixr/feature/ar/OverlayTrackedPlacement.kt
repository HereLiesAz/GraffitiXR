package com.hereliesaz.graffitixr.feature.ar

/**
 * Where [HomographyFallbackOverlay] draws the Overlay design on the tracked target, in the target's
 * own units (half-width 1), so it lands where Overlay mode's untracked 2D draw put it on screen.
 *
 * [halfWidth]/[halfHeight] size the quad for the composited texture (layer scale + Z rotation baked,
 * like `compositeDesignForAr`'s rotated bounds). [panX]/[panY] are y-up target units; [scale] and
 * [rotationZDeg] (GL CCW+) are the whole-design ModeAdjustment, applied by the renderer on top.
 */
internal data class OverlayTrackedPlacement(
    val halfWidth: Float,
    val halfHeight: Float,
    val panX: Float,
    val panY: Float,
    val scale: Float,
    val rotationZDeg: Float,
)

/**
 * Maps Overlay's 2D draw onto the target. The 2D path fits the raw design (`ContentScale.Fit`) into
 * the [viewWidthPx]x[viewHeightPx] screen and then applies the layer and whole-design transforms in
 * screen pixels; here the raw design is instead aspect-fit into the target rectangle, and every
 * screen-pixel distance is converted through the ratio between those two fits — so the design keeps
 * its size, position and rotation RELATIVE to its own fitted frame. Layer offset sits inside the
 * whole-design transform in the 2D path (outer `graphicsLayer` wraps the per-layer one), so it is
 * rotated and scaled by the mode adjustment before joining the mode offset.
 *
 * Returns null when any size is not yet known (no design, unmeasured view).
 */
internal fun overlayTrackedPlacement(
    targetHalfWidth: Float,
    targetHalfHeight: Float,
    designWidthPx: Int,
    designHeightPx: Int,
    viewWidthPx: Int,
    viewHeightPx: Int,
    layerScale: Float,
    layerRotationZDeg: Float,
    layerOffsetXPx: Float,
    layerOffsetYPx: Float,
    modeOffsetXPx: Float,
    modeOffsetYPx: Float,
    modeScale: Float,
    modeRotationDeg: Float,
): OverlayTrackedPlacement? {
    if (designWidthPx <= 0 || designHeightPx <= 0 || viewWidthPx <= 0 || viewHeightPx <= 0) return null
    val fit = fitStandaloneDesignHalfExtents(
        pageWidthUnits = targetHalfWidth * 2f,
        pageHeightUnits = targetHalfHeight * 2f,
        designWidthPx = designWidthPx,
        designHeightPx = designHeightPx,
    ) ?: return null
    val unitsPerBitmapPx = fit.halfWidth * 2f / designWidthPx
    val screenFit = minOf(
        viewWidthPx.toFloat() / designWidthPx,
        viewHeightPx.toFloat() / designHeightPx,
    )
    val unitsPerScreenPx = unitsPerBitmapPx / screenFit

    // Same rotated bounds compositeDesignForAr sizes its texture to.
    val layerRad = Math.toRadians(layerRotationZDeg.toDouble())
    val lc = kotlin.math.abs(kotlin.math.cos(layerRad)).toFloat()
    val ls = kotlin.math.abs(kotlin.math.sin(layerRad)).toFloat()
    val boundsW = (designWidthPx * lc + designHeightPx * ls) * layerScale
    val boundsH = (designWidthPx * ls + designHeightPx * lc) * layerScale

    // Screen space is y-down with CW+ rotation; rotate/scale the layer offset by the mode transform.
    val modeRad = Math.toRadians(modeRotationDeg.toDouble())
    val mc = kotlin.math.cos(modeRad).toFloat()
    val ms = kotlin.math.sin(modeRad).toFloat()
    val panXPx = modeOffsetXPx + modeScale * (layerOffsetXPx * mc - layerOffsetYPx * ms)
    val panYPx = modeOffsetYPx + modeScale * (layerOffsetXPx * ms + layerOffsetYPx * mc)

    return OverlayTrackedPlacement(
        halfWidth = boundsW * 0.5f * unitsPerBitmapPx,
        halfHeight = boundsH * 0.5f * unitsPerBitmapPx,
        panX = panXPx * unitsPerScreenPx,
        panY = -panYPx * unitsPerScreenPx,
        scale = modeScale,
        rotationZDeg = -modeRotationDeg,
    )
}
