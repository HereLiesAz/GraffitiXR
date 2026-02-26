package com.hereliesaz.graffitixr.design.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.hereliesaz.graffitixr.common.model.EditorMode

/**
 * Displays contextual help based on the current active EditorMode.
 */
@Composable
fun OnboardingDialog(
    mode: EditorMode,
    onDismiss: () -> Unit
) {
    val title = when (mode) {
        EditorMode.AR -> "AR Mode"
        EditorMode.TRACE -> "Trace Mode"
        EditorMode.MOCKUP -> "Mockup Mode"
        EditorMode.OVERLAY -> "Overlay Mode"
    }

    val description = when (mode) {
        EditorMode.AR -> "Virtually project images onto real-world surfaces."
        EditorMode.TRACE -> "Project images onto surfaces to trace them in real space."
        EditorMode.MOCKUP -> "Visualize your artwork on 3D surfaces with perspective."
        EditorMode.OVERLAY -> "Compare your progress with a semi-transparent reference."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = { Text(text = description) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it")
            }
        }
    )
}