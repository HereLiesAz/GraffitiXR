package com.hereliesaz.graffitixr

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.xr.compose.material.Material
import androidx.xr.compose.model.Model
import androidx.xr.compose.platform.XrScene
import androidx.xr.compose.spatial.SpatialImage
import coil.compose.rememberAsyncImagePainter
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.hereliesaz.graffitixr.ui.theme.GraffitiXRTheme
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Pose
import androidx.compose.runtime.getValue
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.xr.arcore.rememberTrackedPlanes

@OptIn(ExperimentalPermissionsApi::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GraffitiXRTheme {
                val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
                if (cameraPermissionState.status.isGranted) {
                    MainScreen()
                } else {
                    Column {
                        Text("Camera permission is required to use this app.")
                        androidx.compose.material3.Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                            Text("Request permission")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val arAvailability = ArCoreApk.getInstance().checkAvailability(viewModel.getApplication()).isSupported
    val snackbarHostState = remember { SnackbarHostState() }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        viewModel.onSelectImage(uri)
    }

    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onSnackbarMessageShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Row(modifier = Modifier.padding(padding)) {
            AppNavRail(
                onSelectImage = { launcher.launch("image/*") },
                onRemoveBg = viewModel::onRemoveBgClicked,
                onClearMarkers = { /* Not implemented in this workflow */ },
                onLockMural = viewModel::onLockMural,
                onResetMural = viewModel::onResetMural,
                onSliderSelected = viewModel::onSliderSelected
            )
            Box(modifier = Modifier.fillMaxSize()) {
                if (arAvailability) {
                    ArContent(uiState, onPoseUpdate = viewModel::onCameraPoseChange)
                } else {
                    NonArContent(uiState)
                }

                if (uiState.isProcessing) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                uiState.activeSlider?.let {
                    SliderPopup(
                        sliderType = it,
                        uiState = uiState,
                        viewModel = viewModel,
                        onDismiss = { viewModel.onSliderSelected(null) }
                    )
                }
            }
        }
    }
}

@Composable
fun ArContent(uiState: UiState, onPoseUpdate: (Pose?) -> Unit) {
    val planes = rememberTrackedPlanes()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        delay(5000) // 5 seconds
        if (planes.isEmpty()) {
            snackbarHostState.showSnackbar("Move your phone around to detect surfaces.")
        }
    }

    XrScene(
        modifier = Modifier.fillMaxSize(),
    ) {
        onPoseUpdate(session.camera.pose)
        if (!uiState.placementMode && uiState.lockedPose != null) {
            uiState.imageUri?.let {
                val painter = rememberAsyncImagePainter(it)
                SpatialImage(
                    painter = painter,
                    contentDescription = "Mural",
                    initialPose = uiState.lockedPose,
                    width = 1f,
                    height = 1f,
                    alpha = uiState.opacity,
                    colorFilter = getColorFilter(uiState.saturation, uiState.brightness, uiState.contrast)
                )
            }
            val markerPoses = listOf(
                Pose(floatArrayOf(-0.5f, 0.5f, 0f), floatArrayOf(0f, 0f, 0f, 1f)),
                Pose(floatArrayOf(0.5f, 0.5f, 0f), floatArrayOf(0f, 0f, 0f, 1f)),
                Pose(floatArrayOf(-0.5f, -0.5f, 0f), floatArrayOf(0f, 0f, 0f, 1f)),
                Pose(floatArrayOf(0.5f, -0.5f, 0f), floatArrayOf(0f, 0f, 0f, 1f))
            )
            markerPoses.forEach { markerPose ->
                Model(
                    "models/sphere.obj",
                    initialPose = uiState.lockedPose.compose(markerPose),
                    scale = floatArrayOf(0.05f, 0.05f, 0.05f)
                ) {
                    Material(color = Color.Red)
                }
            }
        }
    }

    if (uiState.placementMode) {
        uiState.imageUri?.let {
            val painter = rememberAsyncImagePainter(it)
            Image(
                painter = painter,
                contentDescription = "Selected Image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                alpha = uiState.opacity,
                colorFilter = getColorFilter(uiState.saturation, uiState.brightness, uiState.contrast)
            )
        }
    }
}

@Composable
fun NonArContent(uiState: UiState) {
    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(modifier = Modifier.fillMaxSize())

        uiState.imageUri?.let {
            val painter = rememberAsyncImagePainter(it)
            Image(
                painter = painter,
                contentDescription = "Selected Image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                alpha = uiState.opacity,
                colorFilter = getColorFilter(uiState.saturation, uiState.brightness, uiState.contrast)
            )
        }
    }
}


@Composable
fun SliderPopup(
    sliderType: SliderType,
    uiState: UiState,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.padding(32.dp),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = sliderType.name, style = MaterialTheme.typography.headlineSmall)
                when (sliderType) {
                    SliderType.Opacity -> Slider(value = uiState.opacity, onValueChange = viewModel::onOpacityChange)
                    SliderType.Contrast -> Slider(value = uiState.contrast, onValueChange = viewModel::onContrastChange, valueRange = 0f..10f)
                    SliderType.Saturation -> Slider(value = uiState.saturation, onValueChange = viewModel::onSaturationChange, valueRange = 0f..10f)
                    SliderType.Brightness -> Slider(value = uiState.brightness, onValueChange = viewModel::onBrightnessChange, valueRange = -1f..1f)
                }
                androidx.compose.material3.Button(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    }
}

fun getColorFilter(saturation: Float, brightness: Float, contrast: Float): ColorFilter {
    val androidColorMatrix = android.graphics.ColorMatrix()
    androidColorMatrix.setSaturation(saturation)

    val brightnessMatrix = android.graphics.ColorMatrix(floatArrayOf(
        1f, 0f, 0f, 0f, brightness * 255,
        0f, 1f, 0f, 0f, brightness * 255,
        0f, 0f, 1f, 0f, brightness * 255,
        0f, 0f, 0f, 1f, 0f
    ))

    val contrastMatrix = android.graphics.ColorMatrix(floatArrayOf(
        contrast, 0f, 0f, 0f, 0f,
        0f, contrast, 0f, 0f, 0f,
        0f, 0f, contrast, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    ))

    androidColorMatrix.postConcat(brightnessMatrix)
    androidColorMatrix.postConcat(contrastMatrix)

    return ColorFilter.colorMatrix(ColorMatrix(androidColorMatrix.array))
}
