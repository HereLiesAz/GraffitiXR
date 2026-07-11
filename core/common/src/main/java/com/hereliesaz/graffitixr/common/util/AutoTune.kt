package com.hereliesaz.graffitixr.common.util

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Summary statistics of an image region, in 0..255 space. [luminance] is Rec.601; [contrast] is the
 * luminance standard deviation. Computed over opaque pixels only (transparent artwork pixels don't
 * describe the artwork).
 */
data class ImageStats(
    val meanR: Float,
    val meanG: Float,
    val meanB: Float,
    val luminance: Float,
    val contrast: Float,
) {
    companion object {
        val NEUTRAL = ImageStats(128f, 128f, 128f, 128f, 0f)
    }
}

/**
 * Adjustment values in the exact conventions `createColorMatrix` + the layer opacity use:
 * [brightness] additive (0 neutral), [contrast]/[saturation]/[colorBalance*] multiplicative
 * (1 neutral), [opacity] 0..1. Fed straight into the layer's adjustment setters.
 */
data class AutoTuneResult(
    val opacity: Float,
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    val colorBalanceR: Float,
    val colorBalanceG: Float,
    val colorBalanceB: Float,
)

/**
 * Pure luminance/colour statistics over ARGB pixels. Transparent pixels (alpha below [minAlpha]) are
 * skipped so isolated artwork is measured by its visible ink, not its empty margins. Returns
 * [ImageStats.NEUTRAL] when nothing is opaque. Kept Android-free so both the wall-frame and artwork
 * paths, and the tests, share one implementation.
 */
fun computeImageStats(pixels: IntArray, minAlpha: Int = 16): ImageStats {
    var sr = 0.0
    var sg = 0.0
    var sb = 0.0
    var sl = 0.0
    var sll = 0.0
    var n = 0
    for (p in pixels) {
        val a = (p ushr 24) and 0xFF
        if (a < minAlpha) continue
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        val lum = 0.299 * r + 0.587 * g + 0.114 * b
        sr += r; sg += g; sb += b; sl += lum; sll += lum * lum
        n++
    }
    if (n == 0) return ImageStats.NEUTRAL
    val inv = 1.0 / n
    val ml = sl * inv
    val variance = (sll * inv - ml * ml).coerceAtLeast(0.0)
    return ImageStats(
        meanR = (sr * inv).toFloat(),
        meanG = (sg * inv).toFloat(),
        meanB = (sb * inv).toFloat(),
        luminance = ml.toFloat(),
        contrast = sqrt(variance).toFloat(),
    )
}

/**
 * Derives "optimal" starting adjustments so freshly-placed artwork reads well against the wall it's
 * projected onto. Conservative and clamped — a sensible first pass the user then fine-tunes with the
 * knobs, not a final grade. Heuristics:
 *  - **opacity**: busier walls (higher [ImageStats.contrast]) get slightly more opaque art so the
 *    wall texture doesn't show through and fight the design.
 *  - **brightness**: nudge the art's luminance toward the wall's so it reads as painted-on, not pasted.
 *  - **contrast**: gently lift low-contrast art.
 *  - **saturation**: a modest pop, more when the wall is drab.
 *  - **colour balance**: tint the art toward the wall's colour cast so it integrates under that light.
 */
fun computeAutoTune(wall: ImageStats, art: ImageStats): AutoTuneResult {
    val opacity = (0.9f - (wall.contrast / 128f) * 0.25f).coerceIn(0.6f, 0.9f)

    val lumDelta = (wall.luminance - art.luminance) / 255f
    val brightness = (lumDelta * 0.25f).coerceIn(-0.2f, 0.2f)

    val contrast = (1f + ((60f - art.contrast).coerceAtLeast(0f) / 60f) * 0.25f).coerceIn(1f, 1.25f)

    val wallColorfulness = colorfulness(wall)
    val saturation = (1.1f + (1f - wallColorfulness) * 0.15f).coerceIn(1.0f, 1.3f)

    val wallMean = ((wall.meanR + wall.meanG + wall.meanB) / 3f).coerceAtLeast(1f)
    val cbR = castNudge(wall.meanR / wallMean)
    val cbG = castNudge(wall.meanG / wallMean)
    val cbB = castNudge(wall.meanB / wallMean)

    return AutoTuneResult(
        opacity = opacity,
        brightness = brightness,
        contrast = contrast,
        saturation = saturation,
        colorBalanceR = cbR,
        colorBalanceG = cbG,
        colorBalanceB = cbB,
    )
}

/** 0..1 how far the mean colour departs from grey — 0 for a neutral wall, higher for a coloured one. */
private fun colorfulness(s: ImageStats): Float {
    val mean = (s.meanR + s.meanG + s.meanB) / 3f
    val spread = (abs(s.meanR - mean) + abs(s.meanG - mean) + abs(s.meanB - mean)) / 3f
    return (spread / 64f).coerceIn(0f, 1f)
}

/** Turn a per-channel wall-cast ratio (channel/meanChannel, ~1 = neutral) into a gentle, clamped tint. */
private fun castNudge(ratio: Float): Float = (1f + (ratio - 1f) * 0.5f).coerceIn(0.85f, 1.15f)
