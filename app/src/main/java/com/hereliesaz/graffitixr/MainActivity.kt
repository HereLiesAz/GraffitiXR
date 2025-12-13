package com.hereliesaz.graffitixr

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.edit
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.hereliesaz.graffitixr.composables.OnboardingScreen
import com.hereliesaz.graffitixr.ui.theme.GraffitiXRTheme
import org.opencv.android.OpenCVLoader

/**
 * The single Activity for the GraffitiXR application.
 *
 * It serves as the entry point and container for the Jetpack Compose UI.
 * Key responsibilities:
 * 1.  Initializing OpenCV (`OpenCVLoader.initLocal()`).
 * 2.  Hosting the [MainScreen] composable.
 * 3.  Handling volume key events for a hidden "Unlock" feature.
 * 4.  Managing initial permissions request (Camera).
 * 5.  Keeping the screen on during Trace mode.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels { MainViewModelFactory() }

    // Hidden "Cheat Code" state for unlocking touch
    private var volUpPressed = false
    private var volDownPressed = false
    private val unlockHandler = Handler(Looper.getMainLooper())
    private val unlockRunnable = Runnable {
        viewModel.setTouchLocked(false)
        Toast.makeText(this, "Screen Unlocked", Toast.LENGTH_SHORT).show()
    }

    /**
     * Intercepts key down events to handle volume button combos.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.repeatCount == 0) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                volUpPressed = true
                checkUnlock()
                if (viewModel.uiState.value.isTouchLocked) return true
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                volDownPressed = true
                checkUnlock()
                if (viewModel.uiState.value.isTouchLocked) return true
            }
        } else {
            if (viewModel.uiState.value.isTouchLocked && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Intercepts key up events to handle volume button combos.
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            volUpPressed = false
            unlockHandler.removeCallbacks(unlockRunnable)
            if (viewModel.uiState.value.isTouchLocked) return true
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            volDownPressed = false
            unlockHandler.removeCallbacks(unlockRunnable)
            if (viewModel.uiState.value.isTouchLocked) return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun checkUnlock() {
        if (volUpPressed && volDownPressed && viewModel.uiState.value.isTouchLocked) {
            unlockHandler.postDelayed(unlockRunnable, 2000) // 2 seconds
        }
    }

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize OpenCV for AR Fingerprinting
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

/**
 * The top-level Composable for the application content.
 * Manages the high-level state of onboarding and permissions before showing the MainScreen.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun AppContent(
    viewModel: MainViewModel,
    onboardingShown: Boolean,
    onOnboardingDismiss: () -> Unit
) {
    var showOnboarding by remember { mutableStateOf(!onboardingShown) }
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

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
        if (showOnboarding) {
            OnboardingScreen(onDismiss = {
                showOnboarding = false
                onOnboardingDismiss()
            })
        } else {
            val cameraPermissionState = rememberPermissionState(
                permission = Manifest.permission.CAMERA
            )

            if (cameraPermissionState.status.isGranted) {
                MainScreen(viewModel = viewModel)
            } else {
                PermissionScreen(
                    onRequestPermission = {
                        cameraPermissionState.launchPermissionRequest()
                    }
                )
            }
        }
    }
}

@Composable
fun PermissionScreen(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Camera permission is required for this app.")
        Button(onClick = onRequestPermission) {
            Text(text = "Grant Permission")
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
