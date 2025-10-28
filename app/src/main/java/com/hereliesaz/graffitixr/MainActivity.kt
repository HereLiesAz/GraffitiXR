package com.hereliesaz.graffitixr

import android.Manifest
import android.os.Bundle
import android.util.Log
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.hereliesaz.graffitixr.ui.theme.GraffitiXRTheme
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {

    private lateinit var arCoreManager: ARCoreManager
    private val viewModel: MainViewModel by viewModels { MainViewModelFactory(arCoreManager) }
    private var arCoreInstallRequested by mutableStateOf(false)


    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        arCoreManager = ARCoreManager(this)
        lifecycle.addObserver(arCoreManager)

        if (!OpenCVLoader.initLocal()) {
            Log.e("OpenCV", "Unable to load OpenCV!")
        } else {
            Log.d("OpenCV", "OpenCV loaded successfully!")
        }
        setContent {
            GraffitiXRTheme {
                AppContent(viewModel = viewModel, arCoreManager = arCoreManager)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        maybeRequestArCoreInstallation()
    }

    private fun maybeRequestArCoreInstallation() {
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !arCoreInstallRequested)) {
                ArCoreApk.InstallStatus.INSTALLED -> {
                }
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    arCoreInstallRequested = true
                }
            }
        } catch (e: UnavailableUserDeclinedInstallationException) {
            Toast.makeText(this, "ARCore installation is required for AR features.", Toast.LENGTH_LONG).show()
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun AppContent(viewModel: MainViewModel, arCoreManager: ARCoreManager) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        val cameraPermissionState = rememberPermissionState(
            permission = Manifest.permission.CAMERA
        )

        if (cameraPermissionState.status.isGranted) {
            MainScreen(viewModel = viewModel, arCoreManager = arCoreManager)
        } else {
            PermissionScreen(
                onRequestPermission = {
                    cameraPermissionState.launchPermissionRequest()
                }
            )
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
        PermissionScreen {

        }
    }
}
