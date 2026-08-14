package com.hereliesaz.graffitixr.design.theme

import androidx.compose.ui.graphics.Color

/**
 * Picks black or white text depending on the luminance of [background], so labels stay legible
 * against any canvas-background preset (including the app's own "White" preset).
 *
 * Mirrors the luminance calculation MainActivity.kt already uses for `navItemColor` on the nav
 * rail, so the two stay visually consistent. Uses the standard Rec. 601 luma weights.
 */
fun contrastColorFor(background: Color, threshold: Float = 0.5f): Color {
    val luminance = 0.299f * background.red + 0.587f * background.green + 0.114f * background.blue
    return if (luminance > threshold) Color.Black else Color.White
}
