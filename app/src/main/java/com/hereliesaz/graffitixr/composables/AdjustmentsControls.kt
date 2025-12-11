package com.hereliesaz.graffitixr.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun UndoRedoRow(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
        }
    }
}

@Composable
// Renders all adjustment knobs in a single row
fun AdjustmentsKnobsRow(
    opacity: Float,
    brightness: Float,
    contrast: Float,
    saturation: Float,
    onOpacityChange: (Float) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onContrastChange: (Float) -> Unit,
    onSaturationChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Knob(
            value = opacity,
            onValueChange = onOpacityChange,
            text = "Opacity",
            color = MaterialTheme.colorScheme.secondary
        )
        Knob(
            value = brightness,
            onValueChange = onBrightnessChange,
            text = "Brightness",
            color = MaterialTheme.colorScheme.onSurface,
            valueRange = -1f..1f
        )
        Knob(
            value = contrast,
            onValueChange = onContrastChange,
            text = "Contrast",
            color = MaterialTheme.colorScheme.tertiary
        )
        Knob(
            value = saturation,
            onValueChange = onSaturationChange,
            text = "Saturation",
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun ColorBalanceKnobsRow(
    colorBalanceR: Float,
    colorBalanceG: Float,
    colorBalanceB: Float,
    onColorBalanceRChange: (Float) -> Unit,
    onColorBalanceGChange: (Float) -> Unit,
    onColorBalanceBChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // Red Knob
        Knob(
            value = colorBalanceR,
            onValueChange = onColorBalanceRChange,
            text = "Red",
            color = Color.Red,
            valueRange = 0f..2f
        )
        // Green Knob
        Knob(
            value = colorBalanceG,
            onValueChange = onColorBalanceGChange,
            text = "Green",
            color = Color.Green,
            valueRange = 0f..2f
        )
        // Blue Knob
        Knob(
            value = colorBalanceB,
            onValueChange = onColorBalanceBChange,
            text = "Blue",
            color = Color.Blue,
            valueRange = 0f..2f
        )
    }
}
