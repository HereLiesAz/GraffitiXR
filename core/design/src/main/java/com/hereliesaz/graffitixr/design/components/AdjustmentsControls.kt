package com.hereliesaz.graffitixr.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun UndoRedoRow(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onMagicClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(end = 48.dp), // Reduced start padding (handled by parent or default)
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
            shadowElevation = 4.dp
        ) {
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
            }
        }

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
            shadowElevation = 4.dp
        ) {
            IconButton(onClick = onMagicClicked) {
                Icon(Icons.Filled.AutoFixHigh, contentDescription = "Magic Align")
            }
        }

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
            shadowElevation = 4.dp
        ) {
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
            }
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
            color = MaterialTheme.colorScheme.secondary,
            valueRange = 0f..1f,
            defaultValue = 1f,
            valueFormatter = { "${(it * 100).roundToInt()}%" }
        )
        Knob(
            value = brightness,
            onValueChange = onBrightnessChange,
            text = "Brightness",
            color = MaterialTheme.colorScheme.onSurface,
            valueRange = -1f..1f,
            defaultValue = 0f,
            valueFormatter = { "${(it * 100).roundToInt()}%" }
        )
        Knob(
            value = contrast,
            onValueChange = onContrastChange,
            text = "Contrast",
            color = MaterialTheme.colorScheme.tertiary,
            valueRange = 0f..2f,
            defaultValue = 1f,
            valueFormatter = { "${(it * 100).roundToInt()}%" }
        )
        Knob(
            value = saturation,
            onValueChange = onSaturationChange,
            text = "Saturation",
            color = MaterialTheme.colorScheme.primary,
            valueRange = 0f..2f,
            defaultValue = 1f,
            valueFormatter = { "${(it * 100).roundToInt()}%" }
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
            valueRange = 0f..2f,
            defaultValue = 1f,
            valueFormatter = { "${(it * 100).roundToInt()}%" }
        )
        // Green Knob
        Knob(
            value = colorBalanceG,
            onValueChange = onColorBalanceGChange,
            text = "Green",
            color = Color.Green,
            valueRange = 0f..2f,
            defaultValue = 1f,
            valueFormatter = { "${(it * 100).roundToInt()}%" }
        )
        // Blue Knob
        Knob(
            value = colorBalanceB,
            onValueChange = onColorBalanceBChange,
            text = "Blue",
            color = Color.Blue,
            valueRange = 0f..2f,
            defaultValue = 1f,
            valueFormatter = { "${(it * 100).roundToInt()}%" }
        )
    }
}
