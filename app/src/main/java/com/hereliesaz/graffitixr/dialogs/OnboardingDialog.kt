package com.hereliesaz.graffitixr.dialogs

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.hereliesaz.graffitixr.EditorMode

@Composable
fun OnboardingDialog(
    editorMode: EditorMode,
    onDismissRequest: () -> Unit
) {
    val title = when (editorMode) {
        EditorMode.STATIC -> "Mock-up Mode"
        EditorMode.NON_AR -> "On-the-Go Mode"
    }

    val description = when (editorMode) {
        EditorMode.STATIC -> "Use this mode to mock-up your artwork on a static background image. You can use two fingers to scale, rotate, and pan the image. For more precise adjustments, toggle to 'Warp' mode to manipulate the perspective with four corner handles."
        EditorMode.NON_AR -> "This mode overlays your image on the live camera feed. It's a quick way to get a preview without full AR tracking. Use two fingers to scale, rotate, and pan the image."
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = title) },
        text = { Text(text = description) },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Got it!")
            }
        }
    )
}