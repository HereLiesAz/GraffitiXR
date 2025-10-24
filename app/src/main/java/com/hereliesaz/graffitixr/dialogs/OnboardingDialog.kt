package com.hereliesaz.graffitixr.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.EditorMode

@Composable
fun OnboardingDialog(
    editorMode: EditorMode,
    onDismissRequest: (Boolean) -> Unit
) {
    val title = when (editorMode) {
        EditorMode.STATIC -> "Mock-up Mode"
        EditorMode.NON_AR -> "On-the-Go Mode"
        EditorMode.AR -> "AR Mode"
    }

    val description = when (editorMode) {
        EditorMode.STATIC -> "Use this mode to mock-up your artwork on a static background image. You can use two fingers to scale, rotate, and pan the image. For more precise adjustments, toggle to 'Warp' mode to manipulate the perspective with four corner handles."
        EditorMode.NON_AR -> "This mode overlays your image on the live camera feed. It's a quick way to get a preview without full AR tracking. Use two fingers to scale, rotate, and pan the image."
        EditorMode.AR -> "This mode uses Augmented Reality to place your artwork in the real world. Point your camera at a surface and tap 'Create Target' to place the image. You can then use two fingers to scale and rotate it."
    }
    var dontShowAgain by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { onDismissRequest(dontShowAgain) },
        title = { Text(text = title) },
        text = {
            Column {
                Text(text = description)
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = dontShowAgain,
                        onCheckedChange = { dontShowAgain = it }
                    )
                    Text("Don't show this again")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismissRequest(dontShowAgain) }) {
                Text("Got it!")
            }
        }
    )
}
