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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.design.theme.AppStrings
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
    onClose: () -> Unit,
    strings: AppStrings,
    // The Library is the app's start destination and has no rail (ConfigureRailItems is gated on
    // !showLibrary, so proj.settings is unreachable from here) — a new user needing a different
    // language or handedness before creating a project had no way to change either. Defaults to a
    // no-op so existing previews/tests that don't care about Settings don't need to supply it.
    onOpenSettings: () -> Unit = {},
    // Set when a just-attempted import failed (corrupt file, wrong type, etc). Surfaced once as a
    // Toast, matching this codebase's existing pattern for reporting async failures to the user
    // (see EditorViewModel's Toast usage), then cleared via onDismissImportError so it doesn't
    // re-fire on the next recomposition.
    importErrorMessage: String? = null,
    onDismissImportError: () -> Unit = {}
) {
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onImportProject(it) }
    }

    val context = LocalContext.current
    LaunchedEffect(importErrorMessage) {
        if (importErrorMessage != null) {
            Toast.makeText(context, importErrorMessage, Toast.LENGTH_LONG).show()
            onDismissImportError()
        }
    }

    // Deletion is destructive and unrecoverable (it wipes the project's AR wall
    // fingerprint along with its design artifacts), so the trash icon on a card
    // never deletes directly — it only stages a project here, and the actual
    // onDeleteProject call happens solely from the confirm button of the
    // AlertDialog rendered below.
    var pendingDeleteProject by remember { mutableStateOf<GraffitiProject?>(null) }

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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.lib.title,
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Justify
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = strings.lib.description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Justify
                    )
                }
                // The only route to Settings before a project exists — see onOpenSettings' doc.
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White)
                }
            }

            // New & Import Project Buttons
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AzButton(
                    text = strings.lib.newProject,
                    onClick = onNewProject,
                    modifier = Modifier.width(120.dp),
                    shape = com.hereliesaz.aznavrail.model.AzButtonShape.RECTANGLE
                )
                Spacer(modifier = Modifier.width(16.dp))
                AzButton(
                    text = strings.lib.importProject,
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
                        text = strings.lib.noProjects,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = strings.lib.noProjectsHint,
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .border(1.dp, Color.White.copy(alpha = 0.15f), CardDefaults.shape)
                                .clickable { onLoadProject(project) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            // The project's design imagery fills the whole box (full-bleed); the
                            // name, date and delete control are overlaid over a bottom scrim so they
                            // stay legible over any artwork.
                            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                                if (project.thumbnailUri != null) {
                                    coil.compose.AsyncImage(
                                        model = project.thumbnailUri,
                                        contentDescription = strings.lib.projectThumbnail,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.35f),
                                        modifier = Modifier.size(72.dp).align(Alignment.Center)
                                    )
                                }

                                // Bottom-up gradient scrim for text contrast.
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .height(110.dp)
                                        .background(
                                            Brush.verticalGradient(
                                                colors = listOf(
                                                    Color.Transparent,
                                                    Color.Black.copy(alpha = 0.8f)
                                                )
                                            )
                                        )
                                )

                                Row(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = project.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                                                .format(Date(project.lastModified)),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White.copy(alpha = 0.85f)
                                        )
                                    }

                                    IconButton(onClick = { pendingDeleteProject = project }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = strings.lib.deleteProjectDesc(project.name),
                                            tint = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Delete confirmation. Dismissing (outside tap, system back, or the
        // Cancel button) only clears the pending state — it never deletes.
        // Only the destructive "Delete" button below invokes onDeleteProject.
        pendingDeleteProject?.let { project ->
            AlertDialog(
                onDismissRequest = { pendingDeleteProject = null },
                title = { Text("Delete \"${project.name}\"?") },
                text = {
                    Text(
                        "This will permanently delete this project, including its design " +
                            "artifacts, thumbnail, and the scanned AR wall data. The wall " +
                            "data cannot be recreated if the real-world surface changes, and " +
                            "this action cannot be undone."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        onDeleteProject(project.id)
                        pendingDeleteProject = null
                    }) {
                        Text(
                            text = strings.editor.delete,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteProject = null }) {
                        Text(strings.common.cancel)
                    }
                }
            )
        }
    }
}
