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