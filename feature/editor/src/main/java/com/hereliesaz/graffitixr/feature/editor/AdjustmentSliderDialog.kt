package com.hereliesaz.graffitixr.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * A dialog that displays a slider for adjusting a specific image property.
 *
 * @param title The title of the dialog, indicating the property being adjusted.
 * @param value The current value of the slider.
 * @param onValueChange The callback to be invoked when the slider's value changes.
 * @param onDismissRequest The callback to be invoked when the dialog is dismissed.
 * @param valueRange The range of values that the slider can take.
 */
@Composable
fun AdjustmentSliderDialog(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onDismissRequest: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Less", color = Color.Gray)
                Text(text = "More", color = Color.Gray)
            }
        }
    }
}