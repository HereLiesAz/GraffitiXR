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

import kotlinx.coroutines.delay

@Composable
fun MuralRoot(muralViewModel: MuralViewModel = viewModel()) {
    val state by muralViewModel.state.collectAsState()
    var hasCameraPermission by remember { mutableStateOf(false) }
    var muralGLSurfaceView by remember { mutableStateOf<MuralGLSurfaceView?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            hasCameraPermission = isGranted
        }
    )

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? ->
            muralViewModel.onImageSelected(uri)
        }
    )

    val markerCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview(),
        onResult = { bitmap ->
            if (bitmap != null) {
                muralViewModel.onMarkerAdded(bitmap)
            }
        }
    )

    LaunchedEffect(key1 = true) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(muralGLSurfaceView) {
        muralGLSurfaceView?.let { view ->
            while (true) {
                val count = view.getTrackedImageCount()
                muralViewModel.updateDetectedMarkersCount(count)

                val stoppedIndices = view.renderer.getStoppedMarkerIndices()
                for (index in stoppedIndices) {
                    muralViewModel.onMarkerCovered(index)
                }

                delay(250)
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (hasCameraPermission) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        MuralGLSurfaceView(context).also {
                            muralGLSurfaceView = it
                            val renderer = MuralRenderer(context)
                            it.setRenderer(renderer)
                            it.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                            renderer.setSession(it.session)
                        }
                    },
                    update = { glSurfaceView ->
                        val muralRenderer = glSurfaceView.renderer as MuralRenderer
                        muralRenderer.updateState(state)
                    }
                )

                when (val appState = state.appState) {
                    is AppState.Initial -> InitialInstructions(onMarkerAdd = { markerCaptureLauncher.launch(null) })
                    is AppState.MarkerCapture -> MarkerCaptureInstructions(
                        detectedMarkersCount = appState.detectedMarkersCount,
                        onMarkerAdd = { markerCaptureLauncher.launch(null) },
                        onImageSelect = { imagePickerLauncher.launch("image/*") }
                    )
                    is AppState.MuralPlacement -> MuralPlacementControls(
                        state = state,
                        onOpacityChange = muralViewModel::onOpacityChanged,
                        onContrastChange = muralViewModel::onContrastChanged,
                        onSaturationChange = muralViewModel::onSaturationChanged
                    )
                }
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
fun InitialInstructions(onMarkerAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Draw high-contrast 'X's on the corners of your mural area, then press 'Add Marker' to capture each one.",
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onMarkerAdd) {
            Text("Add Marker")
        }
    }
}

@Composable
fun MarkerCaptureInstructions(
    detectedMarkersCount: Int,
    onMarkerAdd: () -> Unit,
    onImageSelect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Markers detected: $detectedMarkersCount",
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row {
            Button(onClick = onMarkerAdd) {
                Text("Add Marker")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onImageSelect, enabled = detectedMarkersCount >= 2) {
                Text("Select Image")
            }
        }
    }
}

@Composable
fun MuralPlacementControls(
    state: MuralState,
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
        Text(text = "$label: ${String.format("%.2f", value)}", color = MaterialTheme.colorScheme.onSurface)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
