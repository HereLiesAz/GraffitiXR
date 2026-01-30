package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ColorBalanceDialog(
    title: String,
    valueR: Float,
    valueG: Float,
    valueB: Float,
    onValueRChange: (Float) -> Unit,
    onValueGChange: (Float) -> Unit,
    onValueBChange: (Float) -> Unit,
    onDismissRequest: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = title) },
        text = {
            Column {
                Text(text = "Red")
                Slider(
                    value = valueR,
                    onValueChange = onValueRChange,
                    valueRange = 0f..2f
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "Green")
                Slider(
                    value = valueG,
                    onValueChange = onValueGChange,
                    valueRange = 0f..2f
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "Blue")
                Slider(
                    value = valueB,
                    onValueChange = onValueBChange,
                    valueRange = 0f..2f
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Done")
            }
        }
    )
}
