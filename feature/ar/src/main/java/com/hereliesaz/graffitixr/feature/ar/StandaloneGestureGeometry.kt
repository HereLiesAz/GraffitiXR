package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.graffitixr.feature.ar.rendering.letterboxViewport

/**
 * Convert wall units / camera-frame pixel into wall units / screen pixel for the FIT_CENTER preview.
 */
internal fun standaloneScreenUnitsPerPixel(
    frameUnitsPerPixel: Float,
    frameHeightPixels: Int,
    frameAspect: Float,
    surfaceWidthPixels: Int,
    surfaceHeightPixels: Int,
): Float {
    if (
        !frameUnitsPerPixel.isFinite() ||
        frameUnitsPerPixel <= 0f ||
        frameHeightPixels <= 0
    ) {
        return 0f
    }
    val viewport = letterboxViewport(
        surfaceWidth = surfaceWidthPixels,
        surfaceHeight = surfaceHeightPixels,
        frameAspect = frameAspect,
    ) ?: return 0f
    val viewportHeight = viewport[3]
    if (viewportHeight <= 0) return 0f
    return frameUnitsPerPixel * frameHeightPixels.toFloat() / viewportHeight.toFloat()
}
