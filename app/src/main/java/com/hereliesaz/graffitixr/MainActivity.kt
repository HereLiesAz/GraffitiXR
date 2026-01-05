package com.hereliesaz.graffitixr

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.hereliesaz.graffitixr.composables.OnboardingScreen
import com.hereliesaz.graffitixr.ui.theme.GraffitiXRTheme
import org.opencv.android.OpenCVLoader

/**
 * The single Activity for the GraffitiXR application.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels { MainViewModelFactory() }

    // Hidden "Cheat Code" state for unlocking touch
    private var volUpPressed = false
    private var volDownPressed = false

    override fun onResume() {
        super.onResume()
        if (viewModel.uiState.value.isTouchLocked) {
            viewModel.showUnlockInstructions()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            volUpPressed = true
            if (checkUnlock()) return true

            if (viewModel.uiState.value.isTouchLocked) {
                viewModel.showUnlockInstructions()
                return true
            }
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            volDownPressed = true
            if (checkUnlock()) return true

            if (viewModel.uiState.value.isTouchLocked) {
                viewModel.showUnlockInstructions()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            volUpPressed = false
            if (viewModel.uiState.value.isTouchLocked) return true
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            volDownPressed = false
            if (viewModel.uiState.value.isTouchLocked) return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun checkUnlock(): Boolean {
        if (volUpPressed && volDownPressed && viewModel.uiState.value.isTouchLocked) {
            viewModel.setTouchLocked(false)
            return true
        }
        return false
    }

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!OpenCVLoader.initLocal()) {
            Log.e("OpenCV", "Unable to load OpenCV!")
        } else {
            Log.d("OpenCV", "OpenCV loaded successfully!")
        }

        val prefs = getSharedPreferences("GraffitiXR", Context.MODE_PRIVATE)
        val onboardingShown = prefs.getBoolean("onboarding_shown", false)

        setContent {
            GraffitiXRTheme {
                AppContent(
                    viewModel = viewModel,
                    onboardingShown = onboardingShown,
                    onOnboardingDismiss = {
                        prefs.edit { putBoolean("onboarding_shown", true) }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun AppContent(
    viewModel: MainViewModel,
    onboardingShown: Boolean,
    onOnboardingDismiss: () -> Unit
) {
    var showOnboarding by remember { mutableStateOf(!onboardingShown) }
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val navController = rememberNavController()

    // Keep screen on if in Trace mode and locked
    LaunchedEffect(uiState.editorMode, uiState.isTouchLocked) {
        val activity = context as? android.app.Activity
        activity?.let {
            val window = it.window
            if (uiState.editorMode == EditorMode.TRACE && uiState.isTouchLocked) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val params = window.attributes
                params.screenBrightness = 1.0f
                window.attributes = params
            } else {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val params = window.attributes
                params.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = params
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        NavHost(navController = navController, startDestination = "main") {
            composable("main") {
                if (showOnboarding) {
                    OnboardingScreen(onDismiss = {
                        showOnboarding = false
                        onOnboardingDismiss()
                    })
                } else {
                    val permissions = remember {
                        mutableListOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ).apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.READ_MEDIA_IMAGES)
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                            }
                        }
                    }
                    val permissionState = rememberMultiplePermissionsState(permissions = permissions)

                    val cameraGranted =
                        permissionState.permissions.find { it.permission == Manifest.permission.CAMERA }?.status?.isGranted == true

                    if (cameraGranted) {
                        MainScreen(viewModel = viewModel, navController = navController)
                    } else {
                        PermissionScreen(
                            onRequestPermission = {
                                permissionState.launchMultiplePermissionRequest()
                            }
                        )
                    }
                }
            }

        }
    }
}

@Composable
fun PermissionScreen(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "GraffitiXR needs access to your Camera and Location to function.\n\nWe also request Photo Library access to load your designs and Notification access for updates.",
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        Button(onClick = onRequestPermission) {
            Text(text = "Grant Permissions")
        }
    }
}

@Preview
@Composable
fun PermissionScreenPreview() {
    GraffitiXRTheme {
        PermissionScreen { }
    }
}
