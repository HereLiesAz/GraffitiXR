package com.hereliesaz.graffitixr

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.hereliesaz.graffitixr.common.model.EditorMode
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

    private var isVolumeDownPressed = false
    private var isVolumeUpPressed = false

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
                    val uiState by mainViewModel.uiState.collectAsState()
                    val editorState by editorViewModel.uiState.collectAsState()

                    // Handle Trace Lock Window Attributes (Screen On, Brightness)
                    LaunchedEffect(uiState.isTouchLocked, editorState.editorMode) {
                        if (uiState.isTouchLocked && editorState.editorMode == EditorMode.TRACE) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            val layoutParams = window.attributes
                            layoutParams.screenBrightness = 1.0f
                            window.attributes = layoutParams
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            val layoutParams = window.attributes
                            layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                            window.attributes = layoutParams
                        }
                    }

                    MainScreen(
                        viewModel = mainViewModel,
                        arViewModel = arViewModel,
                        editorViewModel = editorViewModel,
                        dashboardViewModel = dashboardViewModel,
                        navController = navController,
                        onRendererCreated = { /* Renderer lifecycle managed by ArView */ }
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) isVolumeDownPressed = true
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) isVolumeUpPressed = true

        if (isVolumeDownPressed && isVolumeUpPressed) {
            mainViewModel.setTraceLocked(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) isVolumeDownPressed = false
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) isVolumeUpPressed = false
        return super.onKeyUp(keyCode, event)
    }
}