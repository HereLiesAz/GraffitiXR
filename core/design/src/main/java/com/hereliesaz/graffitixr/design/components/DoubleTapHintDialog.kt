package com.hereliesaz.graffitixr.design.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

@Composable
fun DoubleTapHintDialog(
    onDismissRequest: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = "Pro Tip") },
        text = {
            Text(
                text = "Double tap to switch rotation axis.",
                fontSize = 24.sp,
                textAlign = TextAlign.Center
            )
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text("OK")
            }
        }
    )
}