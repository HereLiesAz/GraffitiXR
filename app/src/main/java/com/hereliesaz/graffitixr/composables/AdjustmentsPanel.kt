package com.hereliesaz.graffitixr.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.UiState

@Composable
fun AdjustmentsPanel(
    uiState: UiState,
    showKnobs: Boolean,
    onOpacityChange: (Float) -> Unit,
    onContrastChange: (Float) -> Unit,
    onSaturationChange: (Float) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp), // Lift up a bit
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Knobs
        if (showKnobs) {
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Knob(
                    text = "Opacity",
                    value = uiState.opacity,
                    onValueChange = onOpacityChange,
                    valueRange = 0f..1f
                )
                Knob(
                    text = "Contrast",
                    value = uiState.contrast,
                    onValueChange = onContrastChange,
                    valueRange = 0f..2f
                )
                Knob(
                    text = "Saturation",
                    value = uiState.saturation,
                    onValueChange = onSaturationChange,
                    valueRange = 0f..2f
                )
            }
        }

        // Undo/Redo Buttons (flanking the Toast area)
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp) // Leave space in middle for Toast
        ) {
            IconButton(
                onClick = onUndo,
                enabled = uiState.canUndo,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = Color.White,
                    disabledContentColor = Color.White.copy(alpha = 0.3f)
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
            }

            IconButton(
                onClick = onRedo,
                enabled = uiState.canRedo,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = Color.White,
                    disabledContentColor = Color.White.copy(alpha = 0.3f)
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
            }
        }
    }
}
