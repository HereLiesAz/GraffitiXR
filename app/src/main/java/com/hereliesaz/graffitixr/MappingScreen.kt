package com.hereliesaz.graffitixr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import com.hereliesaz.graffitixr.composables.OverlayScreen
import com.hereliesaz.graffitixr.composables.ProjectLibraryScreen

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    navController: NavController,
    onRendererCreated: (ArRenderer) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        if (uiState.showProjectList) {
            ProjectLibraryScreen(
                projects = uiState.availableProjects,
                onProjectSelected = { project -> 
                    viewModel.openProject(project, navController.context) 
                },
                onNewProject = { viewModel.onNewProject() },
                onDeleteProject = { pid -> viewModel.deleteProject(navController.context, pid) }
            )
        } else {
            // AR View Layer
            ArView(
                viewModel = viewModel,
                onRendererCreated = onRendererCreated,
                modifier = Modifier.fillMaxSize()
            )

            // UI Overlay Layer
            OverlayScreen(
                viewModel = viewModel,
                navController = navController
            )
        }
    }
}
