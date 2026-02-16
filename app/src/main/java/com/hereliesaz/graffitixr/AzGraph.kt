package com.hereliesaz.graffitixr

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController
import com.hereliesaz.aznavrail.AzGraphInterface
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.dashboard.DashboardViewModel
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel

object AzGraph : AzGraphInterface {
    override fun Run(activity: ComponentActivity) {
        val mainActivity = activity as MainActivity

        activity.setContent {
            val navController = rememberNavController()
            val mainViewModel: MainViewModel = hiltViewModel()
            val editorViewModel: EditorViewModel = hiltViewModel()
            val arViewModel: ArViewModel = hiltViewModel()
            val dashboardViewModel: DashboardViewModel = hiltViewModel()

            MainScreen(
                viewModel = mainViewModel,
                editorViewModel = editorViewModel,
                arViewModel = arViewModel,
                dashboardViewModel = dashboardViewModel,
                navController = navController,
                slamManager = mainActivity.slamManager,
                projectRepository = mainActivity.projectRepository,
                onRendererCreated = { /* No-op or handle if needed */ }
            )
        }
    }
}
