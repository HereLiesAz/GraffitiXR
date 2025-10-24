package com.hereliesaz.graffitixr.composables

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ProjectLibraryScreen(
    onNewProject: () -> Unit,
    onLoadProject: () -> Unit,
    onDeleteProject: () -> Unit
) {
    Column {
        Text(text = "Project Library")
        Row {
            Button(onClick = onNewProject) {
                Text(text = "New Project")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = onLoadProject) {
                Text(text = "Load Project")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = onDeleteProject) {
                Text(text = "Delete Project")
            }
        }
    }
}
