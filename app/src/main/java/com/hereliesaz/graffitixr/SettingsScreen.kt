package com.hereliesaz.graffitixr

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A composable function that displays the application's settings screen.
 *
 * This screen provides UI controls for customizing the application's theme,
 * specifically the hue and lightness of the primary accent color used in the nav rail.
 * It is displayed as a full-screen overlay on top of the main content.
 *
 * @param hue The current hue value (ranging from 0f to 360f) for the UI theme color.
 * @param onHueChange A callback lambda that is invoked when the hue slider's value changes.
 *   It passes the new hue value as a parameter.
 * @param lightness The current lightness value (ranging from 0f to 1f) for the UI theme color.
 * @param onLightnessChange A callback lambda that is invoked when the lightness slider's
 *   value changes. It passes the new lightness value as a parameter.
 * @param onDismiss A callback lambda that is invoked when the user clicks the "Close" button,
 *   signaling that the settings screen should be hidden.
 */
@Composable
fun SettingsScreen(
    hue: Float,
    onHueChange: (Float) -> Unit,
    lightness: Float,
    onLightnessChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Hue")
            Slider(value = hue, onValueChange = onHueChange, valueRange = 0f..360f)
            Text("Lightness")
            Slider(value = lightness, onValueChange = onLightnessChange, valueRange = 0f..1f)
            Button(onClick = onDismiss) {
                Text("Close")
            }
        }
    }
}