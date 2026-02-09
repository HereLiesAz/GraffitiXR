package com.hereliesaz.graffitixr.feature.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    ProjectLibraryScreen(
        projects = uiState.availableProjects,
        onLoadProject = { viewModel.openProject(it) },
        onDeleteProject = { /* TODO: Implement delete in ViewModel */ },
        onNewProject = { viewModel.onNewProject(true) }
    )

    LaunchedEffect(Unit) {
        viewModel.loadAvailableProjects()
    }
}
