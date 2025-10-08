package com.hereliesaz.graffitixr.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
    onDismissRequest: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..2f
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
                    valueRange = valueRange
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Green")
                Slider(
                    value = valueG,
                    onValueChange = onValueGChange,
                    valueRange = valueRange
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Blue")
                Slider(
                    value = valueB,
                    onValueChange = onValueBChange,
                    valueRange = valueRange
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismissRequest) {
                Text("Done")
            }
        }
    )
}
