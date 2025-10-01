package com.hereliesaz.graffitixr.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * The main theme for the GraffitiXR application.
 *
 * This composable function wraps the entire UI content of the app, applying the
 * default [MaterialTheme] styling. While it currently does not define a custom
 * color scheme or typography, it serves as the central point for future theming
 * customizations.
 *
 * All composables in the application should be nested within this theme to ensure
 * stylistic consistency.
 *
 * @param content The root composable lambda function that represents the UI content
 *   to which the theme will be applied.
 */
@Composable
fun GraffitiXRTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        content = content
    )
}