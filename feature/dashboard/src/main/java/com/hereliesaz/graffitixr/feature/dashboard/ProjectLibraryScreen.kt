// FILE: feature/dashboard/src/main/java/com/hereliesaz/graffitixr/feature/dashboard/ProjectLibraryScreen.kt
package com.hereliesaz.graffitixr.feature.dashboard

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ProjectLibraryScreen(
    projects: List<GraffitiProject>,
    onLoadProject: (GraffitiProject) -> Unit,
    onDeleteProject: (String) -> Unit,
    onNewProject: () -> Unit,
    onImportProject: (Uri) -> Unit,
    onClose: () -> Unit
) {
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onImportProject(it) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // App intro header — always visible above the action buttons
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "GraffitiXR \n \n",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Justify
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Project your artwork onto a real wall or a canvas using AR or a basic camera overlay. \n\nQuickly create a mockup on a photo to show how the completed work will look. \n\nOr trace an image onto paper by using your device as a lightbox.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Justify
                )
            }

            // New & Import Project Buttons
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AzButton(
                    text = "New",
                    onClick = onNewProject,
                    modifier = Modifier.width(120.dp),
                    shape = com.hereliesaz.aznavrail.model.AzButtonShape.RECTANGLE
                )
                Spacer(modifier = Modifier.width(16.dp))
                AzButton(
                    text = "Import",
                    onClick = { importLauncher.launch("*/*") },
                    modifier = Modifier.width(120.dp),
                    shape = com.hereliesaz.aznavrail.model.AzButtonShape.RECTANGLE
                )
            }

            if (projects.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 32.dp, start = 16.dp, end = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    Text(
                        text = "No projects yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Tap New above to start your first mural.\n\n" +
                            "You'll be taken into the editor where you can:\n" +
                            "  \u2022  Add artwork from the Design menu\n" +
                            "  \u2022  Switch to AR mode to project onto a real wall\n" +
                            "  \u2022  Use Overlay to float your design on the live camera\n" +
                            "  \u2022  Use Mockup to preview on a wall photo\n" +
                            "  \u2022  Use Lightbox to trace your design onto paper",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.65f),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(projects) { project ->
                        Card(
                            modifier = Modifier.fillMaxWidth()
                                .border(1.dp, Color.White.copy(alpha = 0.15f), CardDefaults.shape),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier
                                    .clickable { onLoadProject(project) }
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Thumbnail or Default Icon
                                if (project.thumbnailUri != null) {
                                    coil.compose.AsyncImage(
                                        model = project.thumbnailUri,
                                        contentDescription = "Project Thumbnail",
                                        modifier = Modifier
                                            .size(60.dp)
                                            .background(Color.Black)
                                            .padding(1.dp),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(60.dp).padding(12.dp)
                                    )
                                }
                                Spacer(Modifier.width(16.dp))

                                // Project Info
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = project.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color.White
                                    )
                                    Text(
                                        text = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                                            .format(Date(project.lastModified)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.LightGray
                                    )
                                }

                                // Delete Action
                                IconButton(onClick = { onDeleteProject(project.id) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete project ${project.name}",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}