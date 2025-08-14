package com.hereliesaz.MuralOverlay

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hereliesaz.MuralOverlay.ui.theme.MuralOverlayTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!isCameraPermissionGranted()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        }

        setContent {
            MuralOverlayTheme {
                MuralRoot()
            }
        }
    }

    private fun isCameraPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
fun MuralRoot(muralViewModel: MuralViewModel = viewModel()) {
    val state by muralViewModel.state.collectAsState()
    var hasCameraPermission by remember { mutableStateOf(false) }

    // Launcher for camera permission
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            hasCameraPermission = isGranted
        }
    )

    // Launcher for image selection
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? ->
            muralViewModel.onImageSelected(uri)
        }
    )

    LaunchedEffect(key1 = true) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (hasCameraPermission) {
            Box(modifier = Modifier.fillMaxSize()) {
                ARView(
                    modifier = Modifier.fillMaxSize(),
                    state = state
                )

                Controls(
                    state = state,
                    onImageSelect = { imagePickerLauncher.launch("image/*") },
                    onOpacityChange = muralViewModel::onOpacityChanged,
                    onContrastChange = muralViewModel::onContrastChanged,
                    onSaturationChange = muralViewModel::onSaturationChanged
                )
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Camera permission is required to use this app.")
            }
        }
    }
}

@Composable
fun Controls(
    state: MuralState,
    onImageSelect: () -> Unit,
    onOpacityChange: (Float) -> Unit,
    onContrastChange: (Float) -> Unit,
    onSaturationChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .align(Alignment.BottomCenter),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = onImageSelect) {
            Text("Select Image")
        }
        Spacer(modifier = Modifier.height(16.dp))
        ControlSlider(
            label = "Opacity",
            value = state.opacity,
            onValueChange = onOpacityChange,
            valueRange = 0f..1f
        )
        ControlSlider(
            label = "Contrast",
            value = state.contrast,
            onValueChange = onContrastChange,
            valueRange = 0f..2f // Example range
        )
        ControlSlider(
            label = "Saturation",
            value = state.saturation,
            onValueChange = onSaturationChange,
            valueRange = 0f..2f // Example range
        )
    }
}

@Composable
fun ControlSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "$label: ${String.format("%.2f", value)}", color = Color.White)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
