package com.hereliesaz.graffitixr.ui

import android.Manifest
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hereliesaz.graffitixr.MainViewModel
import com.hereliesaz.graffitixr.feature.ar.presentation.ArScreen
import com.hereliesaz.graffitixr.feature.dashboard.DashboardScreen
import com.hereliesaz.graffitixr.feature.editor.presentation.EditorViewModel
import com.hereliesaz.graffitixr.feature.map.MappingScreen
import com.hereliesaz.graffitixr.ui.components.AzHostActivityLayout
import com.hereliesaz.graffitixr.ui.components.azRailHostItem
import com.hereliesaz.graffitixr.ui.components.azRailSubItem

@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
    editorViewModel: EditorViewModel = hiltViewModel() // Injected for correct scope
) {
    val haptic = LocalHapticFeedback.current
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

    val uiState by viewModel.uiState.collectAsState()
    var currentRoute by remember { mutableStateOf("dashboard") }

    AzHostActivityLayout(
        navigationRail = {
            // HOST: Dashboard
            azRailHostItem(
                selected = currentRoute == "dashboard",
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
                    currentRoute = "dashboard"
                },
                icon = { Icon(Icons.Default.Home, contentDescription = "Dashboard") },
                label = { Text("Home") }
            )

            // HOST: AR/Editor
            azRailHostItem(
                selected = currentRoute == "ar",
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
                    currentRoute = "ar"
                },
                icon = { Icon(Icons.Default.Build, contentDescription = "AR") },
                label = { Text("Create") }
            ) {
                // SUB: Add Layer (routed to EditorViewModel now)
                azRailSubItem(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        editorViewModel.onAddLayer() // Fixed: Using EditorViewModel
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = "Add Layer") },
                    label = { Text("Add Layer") }
                )
                // SUB: Tools
                azRailSubItem(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        /* Open Tools */
                    },
                    icon = { Icon(Icons.Default.Edit, contentDescription = "Tools") },
                    label = { Text("Tools") }
                )
                // SUB: Layers
                azRailSubItem(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        /* Toggle Layers */
                    },
                    icon = { Icon(Icons.Default.Layers, contentDescription = "Layers") },
                    label = { Text("Layers") }
                )
            }

            // HOST: Map
            azRailHostItem(
                selected = currentRoute == "map",
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
                    currentRoute = "map"
                },
                icon = { Icon(Icons.Default.Map, contentDescription = "Map") },
                label = { Text("Map") }
            )

            // HOST: Settings
            azRailHostItem(
                selected = currentRoute == "settings",
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
                    currentRoute = "settings"
                },
                icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                label = { Text("Settings") }
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (currentRoute) {
                "dashboard" -> DashboardScreen()
                "ar" -> ArScreen(
                    onArSessionCreated = { session -> viewModel.onArSessionCreated(session) }
                )
                "map" -> MappingScreen()
                "settings" -> Text("Settings Placeholder", modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}