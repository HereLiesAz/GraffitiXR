package com.hereliesaz.graffitixr

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.navigation.compose.rememberNavController
import com.hereliesaz.aznavrail.AzGraphInterface
import com.hereliesaz.aznavrail.AzHostActivityLayout
import com.hereliesaz.aznavrail.model.AzDockingSide
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer

object AzGraph : AzGraphInterface {
    override fun Run(activity: ComponentActivity) {
        val mainActivity = activity as MainActivity
        activity.setContent {
            com.hereliesaz.graffitixr.design.theme.GraffitiXRTheme {
                val navController = rememberNavController()
                val renderRefState = remember { mutableStateOf<ArRenderer?>(null) }

                // Collect states to force recomposition when they change
                val editorUiState by mainActivity.editorViewModel.uiState.collectAsState()
                val mainUiState by mainActivity.mainViewModel.uiState.collectAsState()

                val isRailVisible = !editorUiState.hideUiForCapture && !mainUiState.isTouchLocked

                // Calculate docking side here to pass down
                val dockingSide = if (editorUiState.isRightHanded) AzDockingSide.LEFT else AzDockingSide.RIGHT

                AzHostActivityLayout(
                    navController = navController,
                    initiallyExpanded = false,
                ) {
                    if (isRailVisible) {
                        with(mainActivity) {
                            configureRail()
                        }
                    }

                    // Pass the AzNavHostScope (this) down so AppContent can enforce the rail's UI boundaries
                    mainActivity.AppContent(this, navController, dockingSide, renderRefState)
                }
            }
        }
    }
}