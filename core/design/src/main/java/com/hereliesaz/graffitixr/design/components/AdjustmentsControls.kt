package com.hereliesaz.graffitixr.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SettingsBackupRestore
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
import com.hereliesaz.graffitixr.design.theme.HotPink
import com.hereliesaz.graffitixr.design.theme.AppStrings
import kotlin.math.roundToInt

@Composable
fun UndoRedoRow(
    canUndo: Boolean,
    canRedo: Boolean,
    undoCount: Int,
    redoCount: Int,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    strings: AppStrings,
    modifier: Modifier = Modifier,
    /** Reset is only meaningful once there is a design to reposition. */
    canReset: Boolean = false,
    /**
     * True when the first Reset press has already flattened placement, so this press restores it.
     * Drives the icon so the button says which of the two it will do.
     */
    isResetActive: Boolean = false,
    onReset: () -> Unit = {},
    /**
     * Color for the undo/redo count labels. Defaults to the theme's onSurface color (which
     * Theme.kt currently pins to white); callers that know the surrounding canvas/background
     * color should pass a luminance-aware color instead, e.g. via
     * [com.hereliesaz.graffitixr.design.theme.contrastColorFor], so the counts stay legible
     * against light canvas-background presets too.
     */
    countColor: Color = MaterialTheme.colorScheme.onSurface,
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
                color = if (canUndo) HotPink else HotPink.copy(alpha = 0.3f),
                shadowElevation = 4.dp
            ) {
                IconButton(onClick = onUndo, enabled = canUndo) {
                    Icon(
                        Icons.AutoMirrored.Filled.Undo,
                        contentDescription = strings.adj.undo,
                        tint = if (canUndo) Color.White else Color.White.copy(alpha = 0.38f)
                    )
                }
            }
            if (undoCount > 0) {
                Text(
                    text = undoCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = countColor,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }

        // Reset sits between undo and redo: a placement escape hatch that is itself reversible.
        Surface(
            shape = CircleShape,
            color = if (canReset) HotPink else HotPink.copy(alpha = 0.3f),
            shadowElevation = 4.dp
        ) {
            IconButton(onClick = onReset, enabled = canReset) {
                Icon(
                    if (isResetActive) Icons.Filled.SettingsBackupRestore else Icons.Filled.RestartAlt,
                    contentDescription = if (isResetActive) strings.adj.restorePlacement else strings.adj.reset,
                    tint = if (canReset) Color.White else Color.White.copy(alpha = 0.38f)
                )
            }
        }

        // Aligned with the third knob (Blue/Contrast)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (redoCount > 0) {
                Text(
                    text = redoCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = countColor,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
            Surface(
                shape = CircleShape,
                // Light background when enabled, dark when disabled
                color = if (canRedo) HotPink else HotPink.copy(alpha = 0.3f),
                shadowElevation = 4.dp
            ) {
                IconButton(onClick = onRedo, enabled = canRedo) {
                    Icon(
                        Icons.AutoMirrored.Filled.Redo,
                        contentDescription = strings.adj.redo,
                        tint = if (canRedo) Color.White else Color.White.copy(alpha = 0.38f)
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
    strings: AppStrings,
    modifier: Modifier = Modifier,
    /** See [Knob]'s labelColor: defaults to white, pass a luminance-aware color for light canvases. */
    labelColor: Color = Color.White
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
            text = strings.adj.opacity,
            color = MaterialTheme.colorScheme.secondary,
            labelColor = labelColor,
            valueRange = 0f..1f,
            defaultValue = 1f,
            valueFormatter = { "${(it * 100).roundToInt()}%" }
        )
        Knob(
            value = brightness,
            onValueChange = onBrightnessChange,
            onValueChangeStart = onAdjustmentStart,
            onValueChangeFinished = onAdjustmentEnd,
            text = strings.adj.brightness,
            color = MaterialTheme.colorScheme.onSurface,
            labelColor = labelColor,
            valueRange = -1f..1f,
            defaultValue = 0f,
            valueFormatter = { "${(it * 100).roundToInt()}%" }
        )
        Knob(
            value = contrast,
            onValueChange = onContrastChange,
            onValueChangeStart = onAdjustmentStart,
            onValueChangeFinished = onAdjustmentEnd,
            text = strings.adj.contrast,
            color = MaterialTheme.colorScheme.tertiary,
            labelColor = labelColor,
            valueRange = 0f..2f,
            defaultValue = 1f,
            valueFormatter = { "${(it * 100).roundToInt()}%" }
        )
        Knob(
            value = saturation,
            onValueChange = onSaturationChange,
            onValueChangeStart = onAdjustmentStart,
            onValueChangeFinished = onAdjustmentEnd,
            text = strings.adj.saturation,
            color = MaterialTheme.colorScheme.primary,
            labelColor = labelColor,
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
    strings: AppStrings,
    modifier: Modifier = Modifier,
    /** See [Knob]'s labelColor: defaults to white, pass a luminance-aware color for light canvases. */
    labelColor: Color = Color.White
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
            text = strings.adj.red,
            color = Color.Red,
            labelColor = labelColor,
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
            text = strings.adj.green,
            color = Color.Green,
            labelColor = labelColor,
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
            text = strings.adj.blue,
            color = Color.Blue,
            labelColor = labelColor,
            valueRange = 0f..2f,
            defaultValue = 1f,
            valueFormatter = { "${(it * 100).roundToInt()}%" }
        )
    }
}
