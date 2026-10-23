package com.hereliesaz.graffitixr.design.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.design.theme.Cyan
import com.hereliesaz.graffitixr.design.theme.Danger
import com.hereliesaz.graffitixr.design.theme.DialogSurface
import com.hereliesaz.graffitixr.design.theme.White

/**
 * The app's one dialog: brand near-black container, display-face title, body-face text, and text
 * buttons in the brand accents (cyan to go ahead, magenta when going ahead destroys something).
 * Every dialog goes through here so they all look alike.
 *
 * @param title the headline, short.
 * @param confirmLabel the action; [onConfirm] runs it.
 * @param destructive the action deletes or replaces something: its label is magenta.
 * @param dismissLabel the way out (null for none); [onDismiss] runs on it and on outside taps.
 * @param content the body: [GxrDialogText] for a sentence, anything for a form.
 */
@Composable
fun GxrDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String? = null,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    content: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        containerColor = DialogSurface,
        titleContentColor = White,
        textContentColor = White,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = content,
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(confirmLabel, color = if (destructive) Danger else Cyan, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = dismissLabel?.let { label ->
            {
                TextButton(onClick = onDismiss) {
                    Text(label, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    )
}

/** A dialog's body sentence, in the body face. */
@Composable
fun GxrDialogText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}
