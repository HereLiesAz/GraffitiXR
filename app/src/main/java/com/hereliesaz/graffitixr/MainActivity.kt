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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.xr.compose.material3.Material
import androidx.xr.compose.material3.Model
import androidx.xr.compose.platform.XrScene
import androidx.xr.compose.spatial.SpatialImage
import coil.compose.rememberAsyncImagePainter
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.hereliesaz.graffitixr.ui.theme.GraffitiXRTheme
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Pose
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.xr.arcore.rememberTrackedPlanes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalPermissionsApi::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GraffitiXRTheme {
                val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
                if (cameraPermissionState.status.isGranted) {
                    val arAvailability = ArCoreApk.getInstance().checkAvailability(this)
                    if (arAvailability.isSupported) {
                        ArScreen()
                    } else {
                        NonArScreen()
                    }
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
fun ArScreen() {
    val imageSettings = rememberImageSettingsState()
    var placementMode by remember { mutableStateOf(true) }
    var lockedPose by remember { mutableStateOf<Pose?>(null) }
    var cameraPose by remember { mutableStateOf<Pose?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        imageSettings.imageUri = uri
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Row(modifier = Modifier.padding(padding)) {
            AppNavRail(
                onSelectImage = { launcher.launch("image/*") },
                onRemoveBg = {
                    imageSettings.imageUri?.let { uri ->
                        scope.launch {
                            isProcessing = true
                            val result = withContext(Dispatchers.IO) {
                                removeBackground(context, uri)
                            }
                            result.onSuccess { newUri ->
                                imageSettings.imageUri = newUri
                            }.onFailure {
                                snackbarHostState.showSnackbar("Background removal failed.")
                            }
                            isProcessing = false
                        }
                    }
                },
                onClearMarkers = { /* Markers are now automatic */ },
                onLockMural = {
                    cameraPose?.let {
                        val translation = floatArrayOf(0f, 0f, -2f)
                        val rotation = floatArrayOf(0f, 0f, 0f, 1f)
                        lockedPose = it.compose(Pose(translation, rotation))
                        placementMode = false
                    }
                },
                onResetMural = {
                    placementMode = true
                    lockedPose = null
                },
                onSliderSelected = { imageSettings.activeSlider = it }
            )
            Box(modifier = Modifier.fillMaxSize()) {
                val planes = rememberTrackedPlanes()

                LaunchedEffect(Unit) {
                    delay(5000) // 5 seconds
                    if (planes.isEmpty()) {
                        snackbarHostState.showSnackbar("Move your phone around to detect surfaces.")
                    }
                }

                XrScene(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    cameraPose = session.camera.pose
                    if (!placementMode && lockedPose != null) {
                        imageSettings.imageUri?.let {
                            val painter = rememberAsyncImagePainter(it)
                            SpatialImage(
                                painter = painter,
                                contentDescription = "Mural",
                                initialPose = lockedPose!!,
                                width = 1f,
                                height = 1f,
                                alpha = imageSettings.opacity,
                                colorFilter = getColorFilter(imageSettings.saturation, imageSettings.brightness, imageSettings.contrast)
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
                                initialPose = lockedPose!!.compose(markerPose),
                                scale = floatArrayOf(0.05f, 0.05f, 0.05f)
                            ) {
                                Material(color = Color.Red)
                            }
                        }
                    }
                }

                if (placementMode) {
                    imageSettings.imageUri?.let {
                        val painter = rememberAsyncImagePainter(it)
                        Image(
                            painter = painter,
                            contentDescription = "Selected Image",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                            alpha = imageSettings.opacity,
                            colorFilter = getColorFilter(imageSettings.saturation, imageSettings.brightness, imageSettings.contrast)
                        )
                    }
                }

                if (isProcessing) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                imageSettings.activeSlider?.let {
                    SliderPopup(
                        sliderType = it,
                        settings = imageSettings,
                        onDismiss = { imageSettings.activeSlider = null }
                    )
                }
            }
        }
    }
}

@Composable
fun NonArScreen() {
    val imageSettings = rememberImageSettingsState()
    var isProcessing by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        imageSettings.imageUri = uri
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Row(modifier = Modifier.padding(padding)) {
            AppNavRail(
                onSelectImage = { launcher.launch("image/*") },
                onRemoveBg = {
                    imageSettings.imageUri?.let { uri ->
                        scope.launch {
                            isProcessing = true
                            val result = withContext(Dispatchers.IO) {
                                removeBackground(context, uri)
                            }
                            result.onSuccess { newUri ->
                                imageSettings.imageUri = newUri
                            }.onFailure {
                                snackbarHostState.showSnackbar("Background removal failed.")
                            }
                            isProcessing = false
                        }
                    }
                },
                onClearMarkers = {},
                onLockMural = {},
                onResetMural = {},
                onSliderSelected = { imageSettings.activeSlider = it }
            )
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    CameraPreview(modifier = Modifier.fillMaxSize())

                    imageSettings.imageUri?.let {
                        val painter = rememberAsyncImagePainter(it)
                        Image(
                            painter = painter,
                            contentDescription = "Selected Image",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                            alpha = imageSettings.opacity,
                            colorFilter = getColorFilter(imageSettings.saturation, imageSettings.brightness, imageSettings.contrast)
                        )
                    }

                    if (isProcessing) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    imageSettings.activeSlider?.let {
                        SliderPopup(
                            sliderType = it,
                            settings = imageSettings,
                            onDismiss = { imageSettings.activeSlider = null }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SliderPopup(
    sliderType: SliderType,
    settings: ImageSettingsState,
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
                    SliderType.Opacity -> Slider(value = settings.opacity, onValueChange = { settings.opacity = it })
                    SliderType.Contrast -> Slider(value = settings.contrast, onValueChange = { settings.contrast = it }, valueRange = 0f..10f)
                    SliderType.Saturation -> Slider(value = settings.saturation, onValueChange = { settings.saturation = it }, valueRange = 0f..10f)
                    SliderType.Brightness -> Slider(value = settings.brightness, onValueChange = { settings.brightness = it }, valueRange = -1f..1f)
                }
                androidx.compose.material3.Button(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    }
}

fun getColorFilter(saturation: Float, brightness: Float, contrast: Float): ColorFilter {
    val matrix = ColorMatrix()
    matrix.setToSaturation(saturation)
    val brightnessMatrix = ColorMatrix(floatArrayOf(
        1f, 0f, 0f, 0f, brightness * 255,
        0f, 1f, 0f, 0f, brightness * 255,
        0f, 0f, 1f, 0f, brightness * 255,
        0f, 0f, 0f, 1f, 0f
    ))
    val contrastMatrix = ColorMatrix(floatArrayOf(
        contrast, 0f, 0f, 0f, 0f,
        0f, contrast, 0f, 0f, 0f,
        0f, 0f, contrast, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    ))
    matrix.postConcat(brightnessMatrix)
    matrix.postConcat(contrastMatrix)
    return ColorFilter.colorMatrix(matrix)
}
