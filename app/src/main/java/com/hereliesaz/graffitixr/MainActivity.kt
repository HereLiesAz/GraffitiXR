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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.edit
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.hereliesaz.graffitixr.composables.OnboardingScreen
import com.hereliesaz.graffitixr.ui.theme.GraffitiXRTheme
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels { MainViewModelFactory() }
    private var volUpPressed = false
    private var volDownPressed = false
    private val unlockHandler = Handler(Looper.getMainLooper())
    private val unlockRunnable = Runnable {
        viewModel.setTouchLocked(false)
        Toast.makeText(this, "Screen Unlocked", Toast.LENGTH_SHORT).show()
    }

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