package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.sphereslam.camera.CameraFillCenter

/**
 * Convert wall units / camera-frame pixel into wall units / screen pixel for the FILL_CENTER preview:
 * one screen pixel spans 1/[CameraFillCenter.scale] frame pixels.
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
        frameHeightPixels <= 0 ||
        !frameAspect.isFinite() ||
        frameAspect <= 0f
    ) {
        return 0f
    }
    val frameWidthPixels = Math.round(frameHeightPixels * frameAspect)
    val scale = CameraFillCenter.scale(
        frameWidth = frameWidthPixels,
        frameHeight = frameHeightPixels,
        viewWidth = surfaceWidthPixels,
        viewHeight = surfaceHeightPixels,
    ) ?: return 0f
    return frameUnitsPerPixel / scale
}
