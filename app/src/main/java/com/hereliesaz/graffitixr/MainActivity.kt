package com.hereliesaz.graffitixr

import android.Manifest
import android.os.Bundle
import android.util.Log
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.hereliesaz.graffitixr.ui.theme.GraffitiXRTheme
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels { MainViewModelFactory() }

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!OpenCVLoader.initLocal()) {
            Log.e("OpenCV", "Unable to load OpenCV!")
        } else {
            Log.d("OpenCV", "OpenCV loaded successfully!")
        }

        VuforiaManager.init(this)

        setContent {
            GraffitiXRTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
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
    }

    override fun onDestroy() {
        super.onDestroy()
        VuforiaManager.deinit()
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
