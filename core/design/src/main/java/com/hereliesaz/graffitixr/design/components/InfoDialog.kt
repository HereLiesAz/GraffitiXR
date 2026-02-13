package com.hereliesaz.graffitixr.design.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * A simple informational dialog using Material3 design.
 *
 * @param title The title text of the dialog.
 * @param content The body text of the dialog.
 * @param onDismiss Callback invoked when the user dismisses the dialog (clicks Close or outside).
 */
@Composable
fun InfoDialog(
    title: String,
    content: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = { Text(text = content) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
