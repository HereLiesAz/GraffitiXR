package com.hereliesaz.graffitixr.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun ColorBalanceDialog(
    onDismissRequest: () -> Unit,
    redValue: Float,
    onRedValueChange: (Float) -> Unit,
    greenValue: Float,
    onGreenValueChange: (Float) -> Unit,
    blueValue: Float,
    onBlueValueChange: (Float) -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Color Balance", style = MaterialTheme.typography.titleMedium)

            Text(text = "Red", style = MaterialTheme.typography.bodyMedium)
            Slider(value = redValue, onValueChange = onRedValueChange, valueRange = 0f..2f)

            Text(text = "Green", style = MaterialTheme.typography.bodyMedium)
            Slider(value = greenValue, onValueChange = onGreenValueChange, valueRange = 0f..2f)

            Text(text = "Blue", style = MaterialTheme.typography.bodyMedium)
            Slider(value = blueValue, onValueChange = onBlueValueChange, valueRange = 0f..2f)
        }
    }
}