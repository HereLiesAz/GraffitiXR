package com.hereliesaz.graffitixr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.hereliesaz.graffitixr.design.theme.GraffitiXRTheme
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.dashboard.DashboardViewModel
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Decoupled ViewModels
    private val mainViewModel: MainViewModel by viewModels()
    private val arViewModel: ArViewModel by viewModels()
    private val editorViewModel: EditorViewModel by viewModels()
    private val dashboardViewModel: DashboardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Lifecycle observation is now handled internally by ViewModels or Composable side-effects
        // removed manual observer registration for the renderer to avoid leaks

        setContent {
            GraffitiXRTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    MainScreen(
                        viewModel = mainViewModel,
                        arViewModel = arViewModel,
                        editorViewModel = editorViewModel,
                        dashboardViewModel = dashboardViewModel,
                        navController = navController,
                        onRendererCreated = { renderer ->
                            // CRITICAL FIX: Only ArViewModel gets the renderer.
                            // MainViewModel is no longer aware of AR implementation details.
                            arViewModel.setArRenderer(renderer)
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mainViewModel.onResume()
        // Note: AR session resumption is handled within ArView/ArRenderer lifecycle
    }

    override fun onPause() {
        super.onPause()
        mainViewModel.onPause()
    }
}