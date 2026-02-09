package com.hereliesaz.graffitixr.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hereliesaz.aznavrail.AzHostActivityLayout
import com.hereliesaz.aznavrail.AzNavHost
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.MainViewModel
import com.hereliesaz.graffitixr.feature.ar.ArScreen
import com.hereliesaz.graffitixr.feature.ar.MappingScreen
import com.hereliesaz.graffitixr.feature.dashboard.DashboardViewModel
import com.hereliesaz.graffitixr.feature.dashboard.ProjectLibraryScreen
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel

@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
    editorViewModel: EditorViewModel = hiltViewModel()
) {
    val haptic = LocalHapticFeedback.current
    val navController = rememberNavController()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        viewModel.onPermissionsResult(permissions)
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    AzHostActivityLayout(
        navController = navController,
        modifier = Modifier.fillMaxSize()
    ) {
        azTheme(
            defaultShape = AzButtonShape.RECTANGLE
        )

        // HOST: Dashboard
        azRailItem(
            id = "dashboard",
            text = "Home",
            route = "dashboard",
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                navController.navigate("dashboard")
            }
        )

        // HOST: AR/Editor (Host Item)
        azRailHostItem(
            id = "ar",
            text = "Create",
            route = "ar",
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                navController.navigate("ar")
            }
        )

        // SUB: Add Layer
        azRailSubItem(
            id = "add_layer",
            hostId = "ar",
            text = "Add Layer",
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                editorViewModel.onAddLayer()
            }
        )
        // SUB: Tools
        azRailSubItem(
            id = "tools",
            hostId = "ar",
            text = "Tools",
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                // TODO: Open tools
            }
        )
        // SUB: Layers
        azRailSubItem(
            id = "layers",
            hostId = "ar",
            text = "Layers",
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                // TODO: Toggle layers
            }
        )

        // HOST: Map
        azRailItem(
            id = "map",
            text = "Map",
            route = "map",
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                navController.navigate("map")
            }
        )

        // HOST: Settings
        azRailItem(
            id = "settings",
            text = "Settings",
            route = "settings",
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                navController.navigate("settings")
            }
        )

        onscreen(Alignment.Center) {
            AzNavHost(startDestination = "dashboard") {
                composable("dashboard") {
                    val dashboardViewModel: DashboardViewModel = hiltViewModel()
                    val state by dashboardViewModel.uiState.collectAsState()
                    LaunchedEffect(Unit) { dashboardViewModel.loadAvailableProjects() }
                    ProjectLibraryScreen(
                        projects = state.availableProjects,
                        onLoadProject = { project ->
                            dashboardViewModel.openProject(project)
                            navController.navigate("ar")
                        },
                        onDeleteProject = { /* dashboardViewModel.deleteProject(it) */ },
                        onNewProject = {
                            dashboardViewModel.onNewProject(true)
                            navController.navigate("ar")
                        }
                    )
                }
                composable("ar") {
                    ArScreen(
                        onArSessionCreated = { session -> viewModel.onArSessionCreated(session) }
                    )
                }
                composable("map") {
                    MappingScreen()
                }
                composable("settings") {
                    // Removed align modifier
                    Text("Settings Placeholder")
                }
            }
        }
    }
}
