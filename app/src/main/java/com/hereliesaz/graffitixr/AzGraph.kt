package com.hereliesaz.graffitixr

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.rememberNavController
import com.hereliesaz.aznavrail.AzGraphInterface
import com.hereliesaz.aznavrail.AzHostActivityLayout
import com.hereliesaz.aznavrail.model.AzDockingSide

object AzGraph : AzGraphInterface {
    override fun Run(activity: ComponentActivity) {
        val mainActivity = activity as MainActivity
        activity.setContent {
            com.hereliesaz.graffitixr.design.theme.GraffitiXRTheme {
                val navController = rememberNavController()

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

                    background(weight = 0) {
                        mainActivity.AppContent(navController, dockingSide)
                    }
                }
            }
        }
    }
}
