package com.hereliesaz.graffitixr.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
    onDismissRequest: (dontShowAgain: Boolean) -> Unit
) {
    var dontShowAgain by remember { mutableStateOf(false) }

    val title = "Welcome to ${editorMode.name} Mode!"
    val message = when (editorMode) {
        EditorMode.STATIC -> "In this mode, you can mockup your artwork on a static background image. Use the controls to adjust the size, position, and orientation of your artwork."
        EditorMode.NON_AR -> "In this mode, you can overlay your artwork on the live camera feed. This is a great way to get a quick preview of your work in the real world."
        EditorMode.AR -> "In this mode, you can use Augmented Reality to place your artwork in the real world. First, you'll need to create an image target by pointing your camera at a real-world object."
        EditorMode.HELP -> "This screen provides information about the different modes and how to use them. You can access this screen at any time by tapping the 'Help' button in the navigation rail."
    }

    AlertDialog(
        onDismissRequest = { onDismissRequest(dontShowAgain) },
        title = { Text(text = title) },
        text = {
            Column {
                Text(text = message)
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = dontShowAgain, onCheckedChange = { dontShowAgain = it })
                    Spacer(modifier = Modifier.width(8.dp))
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
