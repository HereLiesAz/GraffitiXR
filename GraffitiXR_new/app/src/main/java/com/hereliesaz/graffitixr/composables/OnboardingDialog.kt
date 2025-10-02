package com.hereliesaz.graffitixr.composables

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * A reusable dialog for displaying onboarding information.
 *
 * @param title The title of the dialog.
 * @param message The message to be displayed in the dialog.
 * @param onDismiss The callback to be invoked when the dialog is dismissed.
 */
@Composable
fun OnboardingDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = { Text(text = message) },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Got it!")
            }
        }
    )
}