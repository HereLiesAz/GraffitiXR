package com.hereliesaz.graffitixr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.rememberNavController
import com.hereliesaz.graffitixr.design.theme.GraffitiXRTheme
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.feature.dashboard.DashboardViewModel
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The Single Activity for the application.
 * Sets up the Compose content, Hilt injection, and the top-level Navigation Graph.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var slamManager: SlamManager

    private val mainViewModel: MainViewModel by viewModels()
    private val editorViewModel: EditorViewModel by viewModels()
    private val arViewModel: ArViewModel by viewModels()
    private val dashboardViewModel: DashboardViewModel by viewModels()

    private var arRenderer: ArRenderer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            GraffitiXRTheme {
                val navController = rememberNavController()
                val mainState by mainViewModel.uiState.collectAsState()

                MainScreen(
                    viewModel = mainViewModel,
                    editorViewModel = editorViewModel,
                    arViewModel = arViewModel,
                    dashboardViewModel = dashboardViewModel,
                    navController = navController,
                    slamManager = slamManager, // PASSED DOWN
                    onRendererCreated = { renderer ->
                        arRenderer = renderer
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}