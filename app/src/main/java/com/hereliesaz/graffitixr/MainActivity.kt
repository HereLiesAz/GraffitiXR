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
import androidx.compose.foundation.layout.size
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
import androidx.core.view.WindowCompat
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.compose.subspace.layout.rotate
import androidx.xr.runtime.math.Quaternion
import coil.compose.rememberAsyncImagePainter
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.hereliesaz.aznavrail.AzNavRail
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.hereliesaz.graffitixr.ui.theme.GraffitiXRTheme
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Pose
import com.hereliesaz.graffitixr.SliderType
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel

/**
 * The main entry point of the GraffitiXR application.
 * This activity handles camera permission requests and sets up the main UI.
 */
@OptIn(ExperimentalPermissionsApi::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            GraffitiXRTheme {
                val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
                if (cameraPermissionState.status.isGranted) {
                    MainScreen()
                } else {
                    // A simple UI to request camera permission.
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
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

/**
 * The main screen of the application, which orchestrates the UI components.
 * It displays the appropriate content based on AR availability and handles user interactions.
 *
 * @param viewModel The [MainViewModel] that holds the application's state and logic.
 */
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

    // Shows a snackbar message when one is available in the UI state.
    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onSnackbarMessageShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            // AR or Non-AR content is drawn first, covering the whole screen.
            if (arAvailability) {
                ArContent(uiState)
            } else {
                NonArContent(uiState)
            }

            Row(modifier = Modifier.padding(padding)) {
                var selected by remember { mutableStateOf<SliderType?>(null) }
                AzNavRail(Modifier, true, true, {
                    SliderType.values().forEach { sliderType ->
                        azRailItem(
                            sliderType.name,
                            sliderType.name,
                            if (selected == sliderType) Color.White else Color.Gray
                        ) {
                            selected = sliderType
                            viewModel.onSliderSelected(sliderType)
                        }
                    }
                })
            }

            // Show a loading indicator when processing.
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

            // Show the slider popup when a slider is active.
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

/**
 * Composable for rendering the Augmented Reality content.
 * It displays the virtual mural and markers in the AR scene.
 *
 * @param uiState The current state of the UI.
 */
@Composable
fun ArContent(uiState: UiState) {
    Subspace {
        if (!uiState.placementMode && uiState.lockedPose != null) {
            uiState.imageUri?.let {
                val pose = uiState.lockedPose!!
                val rotation = pose.rotationQuaternion
                val translation = pose.translation
                SpatialPanel(
                    modifier = SubspaceModifier
                        .offset(
                            x = translation[0].dp,
                            y = translation[1].dp,
                            z = translation[2].dp
                        )
                        .rotate(
                            Quaternion(
                                rotation[0],
                                rotation[1],
                                rotation[2],
                                rotation[3]
                            )
                        )
                ) {
                    val painter = rememberAsyncImagePainter(it)
                    Image(
                        painter = painter,
                        contentDescription = "Selected Image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        alpha = uiState.opacity,
                        colorFilter = getColorFilter(
                            uiState.saturation,
                            uiState.brightness,
                            uiState.contrast
                        )
                    )
                }
            }
            val markerPoses = listOf(
                Pose(floatArrayOf(-0.5f, 0.5f, 0f), floatArrayOf(0f, 0f, 0f, 1f)),
                Pose(floatArrayOf(0.5f, 0.5f, 0f), floatArrayOf(0f, 0f, 0f, 1f)),
                Pose(floatArrayOf(-0.5f, -0.5f, 0f), floatArrayOf(0f, 0f, 0f, 1f)),
                Pose(floatArrayOf(0.5f, -0.5f, 0f), floatArrayOf(0f, 0f, 0f, 1f))
            )
            markerPoses.forEach { markerPose ->
                val rotation = markerPose.rotationQuaternion
                val translation = markerPose.translation
                SpatialPanel(
                    modifier = SubspaceModifier
                        .offset(
                            x = translation[0].dp,
                            y = translation[1].dp,
                            z = translation[2].dp
                        )
                        .rotate(
                            Quaternion(
                                rotation[0],
                                rotation[1],
                                rotation[2],
                                rotation[3]
                            )
                        )
                ) {
                    Box(modifier = Modifier.size(10.dp).background(Color.Red))
                }
            }
        }
    }

    // In placement mode, show the selected image overlaid on the screen.
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

/**
 * Composable for rendering the content when AR is not available.
 * It shows a camera preview with the selected image overlaid.
 *
 * @param uiState The current state of the UI.
 */
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

/**
 * A popup that displays a slider for adjusting image properties.
 *
 * @param sliderType The type of slider to display (e.g., Opacity, Contrast).
 * @param uiState The current state of the UI.
 * @param viewModel The [MainViewModel] to handle slider value changes.
 * @param onDismiss A callback to dismiss the popup.
 */
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

/**
 * Creates a [ColorFilter] from saturation, brightness, and contrast values.
 *
 * @param saturation The saturation level.
 * @param brightness The brightness level.
 * @param contrast The contrast level.
 * @return A [ColorFilter] that can be applied to an image.
 */
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
