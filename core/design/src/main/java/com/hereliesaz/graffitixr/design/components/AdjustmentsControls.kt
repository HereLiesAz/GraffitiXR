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
import androidx.compose.material3.Text
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
    undoCount: Int,
    redoCount: Int,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onMagicClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Aligned with the first knob (Red/Opacity)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                // Light background when enabled, dark when disabled
                color = if (canUndo) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
                shadowElevation = 4.dp
            ) {
                IconButton(onClick = onUndo, enabled = canUndo) {
                    Icon(
                        Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "Undo",
                        tint = if (canUndo) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }
            if (undoCount > 0) {
                Text(
                    text = undoCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }

        // Aligned with the second knob (Green/Brightness) - Magic Wand
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
            shadowElevation = 4.dp
        ) {
            IconButton(onClick = onMagicClicked) {
                Icon(Icons.Filled.AutoFixHigh, contentDescription = "Magic Align")
            }
        }

        // Aligned with the third knob (Blue/Contrast)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (redoCount > 0) {
                Text(
                    text = redoCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
            Surface(
                shape = CircleShape,
                // Light background when enabled, dark when disabled
                color = if (canRedo) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
                shadowElevation = 4.dp
            ) {
                IconButton(onClick = onRedo, enabled = canRedo) {
                    Icon(
                        Icons.AutoMirrored.Filled.Redo,
                        contentDescription = "Redo",
                        tint = if (canRedo) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
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
    onAdjustmentStart: () -> Unit,
    onAdjustmentEnd: () -> Unit,
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
            onValueChangeStart = onAdjustmentStart,
            onValueChangeFinished = onAdjustmentEnd,
            text = "Opacity",
            color = MaterialTheme.colorScheme.secondary,
            valueRange = 0f..1f,
            defaultValue = 1f,
            valueFormatter = { "${(it * 100).roundToInt()}%" }
        )
        Knob(
            value = brightness,
            onValueChange = onBrightnessChange,
            onValueChangeStart = onAdjustmentStart,
            onValueChangeFinished = onAdjustmentEnd,
            text = "Brightness",
            color = MaterialTheme.colorScheme.onSurface,
            valueRange = -1f..1f,
            defaultValue = 0f,
            valueFormatter = { "${(it * 100).roundToInt()}%" }
        )
        Knob(
            value = contrast,
            onValueChange = onContrastChange,
            onValueChangeStart = onAdjustmentStart,
            onValueChangeFinished = onAdjustmentEnd,
            text = "Contrast",
            color = MaterialTheme.colorScheme.tertiary,
            valueRange = 0f..2f,
            defaultValue = 1f,
            valueFormatter = { "${(it * 100).roundToInt()}%" }
        )
        Knob(
            value = saturation,
            onValueChange = onSaturationChange,
            onValueChangeStart = onAdjustmentStart,
            onValueChangeFinished = onAdjustmentEnd,
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
    onAdjustmentStart: () -> Unit,
    onAdjustmentEnd: () -> Unit,
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
            onValueChangeStart = onAdjustmentStart,
            onValueChangeFinished = onAdjustmentEnd,
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
            onValueChangeStart = onAdjustmentStart,
            onValueChangeFinished = onAdjustmentEnd,
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
            onValueChangeStart = onAdjustmentStart,
            onValueChangeFinished = onAdjustmentEnd,
            text = "Blue",
            color = Color.Blue,
            valueRange = 0f..2f,
            defaultValue = 1f,
            valueFormatter = { "${(it * 100).roundToInt()}%" }
        )
    }
}
