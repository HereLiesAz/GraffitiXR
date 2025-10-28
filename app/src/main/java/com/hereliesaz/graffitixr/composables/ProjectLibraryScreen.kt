package com.hereliesaz.graffitixr.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ProjectLibraryScreen(
    projects: List<String>,
    onLoadProject: (String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onNewProject: () -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = "Project Library")
        Button(onClick = onNewProject) {
            Text(text = "New Project")
        }
        LazyColumn {
            items(projects) { project ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = project)
                    Row {
                        Button(onClick = { onLoadProject(project) }) {
                            Text(text = "Load")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { onDeleteProject(project) }) {
                            Text(text = "Delete")
                        }
                    }
                }
            }
        }
    }
}
