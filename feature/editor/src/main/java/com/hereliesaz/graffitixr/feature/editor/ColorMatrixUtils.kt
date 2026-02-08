package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.graphics.ColorMatrix

/**
 * Creates a ColorMatrix for adjusting image properties.
 *
 * @param saturation The saturation level (1.0f is normal).
 * @param contrast The contrast level (1.0f is normal).
 * @param brightness The brightness level (0.0f is normal).
 * @param colorBalanceR The red color balance (1.0f is normal).
 * @param colorBalanceG The green color balance (1.0f is normal).
 * @param colorBalanceB The blue color balance (1.0f is normal).
 * @return A [ColorMatrix] combining the given adjustments.
 */
fun createColorMatrix(
    saturation: Float,
    contrast: Float,
    brightness: Float,
    colorBalanceR: Float,
    colorBalanceG: Float,
    colorBalanceB: Float
): ColorMatrix {
    return ColorMatrix().apply {
        setToSaturation(saturation)
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, (1 - contrast) * 128,
                0f, contrast, 0f, 0f, (1 - contrast) * 128,
                0f, 0f, contrast, 0f, (1 - contrast) * 128,
                0f, 0f, 0f, 1f, 0f
            )
        )

        val b = brightness * 255f
        val brightnessMatrix = ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, b,
                0f, 1f, 0f, 0f, b,
                0f, 0f, 1f, 0f, b,
                0f, 0f, 0f, 1f, 0f
            )
        )

        val colorBalanceMatrix = ColorMatrix(
            floatArrayOf(
                colorBalanceR, 0f, 0f, 0f, 0f,
                0f, colorBalanceG, 0f, 0f, 0f,
                0f, 0f, colorBalanceB, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        timesAssign(contrastMatrix)
        timesAssign(brightnessMatrix)
        timesAssign(colorBalanceMatrix)
    }
}
