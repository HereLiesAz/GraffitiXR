package com.hereliesaz.graffitixr.dialogs

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

@Composable
fun SaveProjectDialog(
    onDismissRequest: () -> Unit,
    onSaveRequest: (String) -> Unit
) {
    val (projectName, setProjectName) = remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = "Save Project") },
        text = {
            TextField(
                value = projectName,
                onValueChange = setProjectName,
                label = { Text("Project Name") }
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (projectName.isNotBlank()) {
                        onSaveRequest(projectName)
                    }
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel")
            }
        }
    )
}
