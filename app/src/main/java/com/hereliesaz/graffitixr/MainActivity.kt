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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.xr.compose.ARScene
import androidx.xr.compose.rememberARCamera
import androidx.xr.compose.rememberARPlanes
import androidx.xr.compose.spatial.SubspaceComponent
import androidx.xr.compose.spatial.rememberSubspace
import androidx.xr.runtime.Config
import androidx.xr.runtime.PlaneTrackingMode
import androidx.xr.runtime.TrackingState
import androidx.xr.runtime.rememberDefaultRun
import androidx.xr.runtime.rememberSession
import coil.compose.rememberAsyncImagePainter
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.hereliesaz.aznavrail.AzNavRail
import com.hereliesaz.graffitixr.ui.theme.GraffitiXRTheme

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
                val permissionStates = rememberMultiplePermissionsState(
                    listOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.SCENE_UNDERSTANDING_COARSE
                    )
                )
                if (permissionStates.allPermissionsGranted) {
                    MainScreen()
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Camera and Scene Understanding permissions are required to use this app.")
                        androidx.compose.material3.Button(onClick = { permissionStates.launchMultiplePermissionRequest() }) {
                            Text("Request permissions")
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
    val snackbarHostState = remember { SnackbarHostState() }
    val navRailColor = Color.hsl(uiState.hue, 1f, uiState.lightness)
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
        Box(modifier = Modifier.fillMaxSize()) {
            ArContent(uiState, viewModel)

            Row(modifier = Modifier.padding(padding)) {
                var selected by remember { mutableStateOf<SliderType?>(null) }
                AzNavRail {
                    azSettings(isLoading = uiState.isProcessing)
                    azRailItem(id = "select_image", text = "Image", color = if (uiState.imageUri != null) Color.Green else navRailColor) {
                        launcher.launch("image/*")
                    }
                    azRailItem(id = "settings", text = "Settings", color = navRailColor) {
                        viewModel.onSettingsClicked(true)
                    }
                    azRailItem(id = "remove_bg", text = "Remove BG", color = navRailColor) {
                        viewModel.onRemoveBg()
                    }
                    azRailItem(id = "lock_mural", text = "Lock", color = if (uiState.muralPoses.isNotEmpty()) Color.Green else navRailColor) {
                        viewModel.onLockMural()
                    }
                    azRailItem(id = "undo_mural", text = "Undo", color = navRailColor) {
                        viewModel.onUndoMural()
                    }
                    azRailItem(id = "clear_markers", text = "Clear", color = navRailColor) {
                        viewModel.onClearMarkers()
                    }
                    SliderType.values().forEach { sliderType ->
                        azRailItem(
                            id = sliderType.name,
                            text = sliderType.name,
                            color = if (selected == sliderType) navRailColor else Color.Gray,
                        ) {
                            selected = sliderType
                            viewModel.onSliderSelected(sliderType)
                        }
                    }
                }
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

            if (uiState.showSettings) {
                SettingsScreen(
                    hue = uiState.hue,
                    onHueChange = viewModel::onHueChange,
                    lightness = uiState.lightness,
                    onLightnessChange = viewModel::onLightnessChange,
                    onDismiss = { viewModel.onSettingsClicked(false) }
                )
            }
        }
    }
}

/**
 * Composable for rendering the Augmented Reality content.
 */
@Composable
fun ArContent(uiState: UiState, viewModel: MainViewModel) {
    val run = rememberDefaultRun()
    val session = rememberSession(run)
    val camera = rememberARCamera(session)
    val planes = rememberARPlanes(session)
    val subspace = rememberSubspace()

    // Perform hit testing to find a placement pose
    LaunchedEffect(session.frame, uiState.placementMode) {
        if (uiState.placementMode) {
            val frame = session.frame ?: return@LaunchedEffect
            val hitResults = frame.hitTest(frame.camera.displayOrientedPose, 0f, 0f)
            val hit = hitResults.firstOrNull {
                it.trackable is androidx.xr.arcore.Plane && (it.trackable as androidx.xr.arcore.Plane).isPoseInPolygon(it.hitPose)
            }

            if (hit != null) {
                viewModel.onPlacementPoseChange(hit.hitPose)
            } else {
                viewModel.onPlacementPoseChange(null)
            }
        } else {
            viewModel.onPlacementPoseChange(null)
        }
    }

    LaunchedEffect(camera.pose) {
        viewModel.onCameraPoseChange(camera.pose)
    }

    ARScene(
        modifier = Modifier.fillMaxSize(),
        camera = camera,
        run = run,
        session = session,
        planes = planes,
        subspace = subspace,
        onSessionCreated = { session ->
            val config = Config(session).apply {
                planeTrackingMode = PlaneTrackingMode.HORIZONTAL_AND_VERTICAL
            }
            session.configure(config)
            session.resume()
        }
    ) {
        // Draw the detected planes for visualization
        planes.value.forEach { plane ->
            if (plane.trackingState == TrackingState.TRACKING && plane.subsumedBy == null) {
                subspace.Place(
                    anchor = session.createAnchor(plane.centerPose),
                    component = SubspaceComponent(
                        remember(plane) {
                            {
                                Box(
                                    modifier = Modifier
                                        .size(plane.extentX.dp, plane.extentZ.dp)
                                        .background(Color.White.copy(alpha = 0.5f))
                                )
                            }
                        }
                    )
                )
            }
        }

        // Draw a mural for each pose in the list
        uiState.imageUri?.let { uri ->
            uiState.muralPoses.forEach { pose ->
                subspace.Place(
                    anchor = session.createAnchor(pose),
                    component = SubspaceComponent(
                        remember(uri, uiState, pose) {
                            {
                                val painter = rememberAsyncImagePainter(uri)
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
                    )
                )
            }
        }

        // Draw the placement preview
        if (uiState.placementMode) {
            uiState.placementPose?.let { pose ->
                subspace.Place(
                    anchor = session.createAnchor(pose),
                    component = SubspaceComponent(
                        remember(uiState.imageUri, uiState.opacity) {
                            {
                                uiState.imageUri?.let { uri ->
                                    val painter = rememberAsyncImagePainter(uri)
                                    Image(
                                        painter = painter,
                                        contentDescription = "Placement Preview",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit,
                                        alpha = uiState.opacity * 0.5f, // Semi-transparent
                                        colorFilter = getColorFilter(
                                            uiState.saturation,
                                            uiState.brightness,
                                            uiState.contrast
                                        )
                                    )
                                }
                            }
                        }
                    )
                )
            }
        }
    }
}


/**
 * A popup that displays a slider for adjusting image properties.
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
