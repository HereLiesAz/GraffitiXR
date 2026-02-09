package com.hereliesaz.graffitixr.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hereliesaz.aznavrail.AzHostActivityLayout
import com.hereliesaz.aznavrail.AzNavHost
import com.hereliesaz.graffitixr.MainViewModel
import com.hereliesaz.graffitixr.feature.ar.ArScreen
import com.hereliesaz.graffitixr.feature.ar.MappingScreen
import com.hereliesaz.graffitixr.feature.dashboard.DashboardScreen
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel

@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
    editorViewModel: EditorViewModel = hiltViewModel()
) {
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

    AzHostActivityLayout(navController = navController) {

        // Dashboard
        azRailItem(
            id = "dashboard",
            text = "Home",
            route = "dashboard"
        )

        // AR / Create Host
        azRailHostItem(
            id = "ar",
            text = "Create",
            route = "ar"
        )

        // AR Sub Items
        azRailSubItem(
            id = "add_layer",
            hostId = "ar",
            text = "Add Layer",
            route = "ar", // Stay on AR screen
            onClick = { editorViewModel.onAddLayer() }
        )

        azRailSubItem(
            id = "tools",
            hostId = "ar",
            text = "Tools",
            route = "ar"
        )

        azRailSubItem(
            id = "layers",
            hostId = "ar",
            text = "Layers",
            route = "ar"
        )

        // Map
        azRailItem(
            id = "map",
            text = "Map",
            route = "map"
        )

        // Settings
        azRailItem(
            id = "settings",
            text = "Settings",
            route = "settings"
        )

        onscreen(alignment = Alignment.Center) {
            AzNavHost(startDestination = "dashboard") {
                composable("dashboard") { DashboardScreen() }
                composable("ar") {
                    ArScreen(
                        onArSessionCreated = { session -> viewModel.onArSessionCreated(session) }
                    )
                }
                composable("map") { MappingScreen() }
                composable("settings") {
                    androidx.compose.material3.Text("Settings Placeholder")
                }
            }
        }
    }
}
