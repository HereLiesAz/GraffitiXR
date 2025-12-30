package com.hereliesaz.graffitixr.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.hereliesaz.graffitixr.EditorMode

@Composable
fun OnboardingDialog(
    editorMode: EditorMode,
    onDismiss: () -> Unit
) {
    val title = when (editorMode) {
        EditorMode.AR -> "Welcome to AR Grid Mode!"
        else -> "Welcome to ${editorMode.name} Mode!"
    }

    val message = when (editorMode) {
        EditorMode.STATIC -> "In this mode, you can mockup your artwork on a static background image. Use the controls to adjust the size, position, and orientation of your artwork."
        EditorMode.OVERLAY -> "In this mode, you can overlay your artwork on the live camera feed. This is a great way to get a quick preview of your work in the real world."
        EditorMode.TRACE -> "In this mode, your device acts as a lightbox. Place a piece of paper over the screen to trace your artwork. You can lock the screen to prevent accidental touches."
        EditorMode.AR -> "In this mode, you can use Augmented Reality to place your artwork in the real world. First, you'll need to create an image target by pointing your camera at a real-world object."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column {
                Text(text = message)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it!")
            }
        }
    )
}