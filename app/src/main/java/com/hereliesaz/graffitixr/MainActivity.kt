package com.hereliesaz.graffitixr

import android.Manifest
import android.net.Uri
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
    var placementMode by remember { mutableStateOf(true) }
    var lockedPose by remember { mutableStateOf<Pose?>(null) }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var cameraPose by remember { mutableStateOf<Pose?>(null) }
    var activeSlider by remember { mutableStateOf<SliderType?>(null) }

    var opacity by remember { mutableStateOf(1f) }
    var contrast by remember { mutableStateOf(1f) }
    var saturation by remember { mutableStateOf(1f) }
    var brightness by remember { mutableStateOf(0f) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        imageUri = uri
    }

    Row {
        AppNavRail(
            onSelectImage = { launcher.launch("image/*") },
            onRemoveBg = {
                imageUri?.let { uri ->
                    scope.launch {
                        val newUri = removeBackground(context, uri)
                        if (newUri != null) {
                            imageUri = newUri
                        }
                    }
                }
            },
            onClearMarkers = { /* Markers are now automatic */ },
            onLockMural = {
                cameraPose?.let {
                    // Place the mural 2 meters in front of the camera
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
            onSliderSelected = { activeSlider = it }
        )
        Box(modifier = Modifier.fillMaxSize()) {
            XrScene(
                modifier = Modifier.fillMaxSize(),
            ) {
                cameraPose = session.camera.pose
                if (!placementMode && lockedPose != null) {
                    imageUri?.let {
                        val painter = rememberAsyncImagePainter(it)
                        SpatialImage(
                            painter = painter,
                            contentDescription = "Mural",
                            initialPose = lockedPose!!,
                            width = 1f,
                            height = 1f,
                            alpha = opacity,
                            colorFilter = getColorFilter(saturation, brightness, contrast)
                        )
                    }
                    // Place markers at the corners of the 1x1 spatial image
                    val markerPoses = listOf(
                        Pose(floatArrayOf(-0.5f, 0.5f, 0f), floatArrayOf(0f, 0f, 0f, 1f)), // Top-left
                        Pose(floatArrayOf(0.5f, 0.5f, 0f), floatArrayOf(0f, 0f, 0f, 1f)), // Top-right
                        Pose(floatArrayOf(-0.5f, -0.5f, 0f), floatArrayOf(0f, 0f, 0f, 1f)), // Bottom-left
                        Pose(floatArrayOf(0.5f, -0.5f, 0f), floatArrayOf(0f, 0f, 0f, 1f)) // Bottom-right
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
                imageUri?.let {
                    val painter = rememberAsyncImagePainter(it)
                    Image(
                        painter = painter,
                        contentDescription = "Selected Image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        alpha = opacity,
                        colorFilter = getColorFilter(saturation, brightness, contrast)
                    )
                }
            }
            activeSlider?.let {
                SliderPopup(
                    sliderType = it,
                    opacity = opacity,
                    contrast = contrast,
                    saturation = saturation,
                    brightness = brightness,
                    onOpacityChange = { opacity = it },
                    onContrastChange = { contrast = it },
                    onSaturationChange = { saturation = it },
                    onBrightnessChange = { brightness = it },
                    onDismiss = { activeSlider = null }
                )
            }
        }
    }
}

@Composable
fun NonArScreen() {
    var activeSlider by remember { mutableStateOf<SliderType?>(null) }
    var opacity by remember { mutableStateOf(1f) }
    var contrast by remember { mutableStateOf(1f) }
    var saturation by remember { mutableStateOf(1f) }
    var brightness by remember { mutableStateOf(0f) }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        imageUri = uri
    }

    Row {
        AppNavRail(
            onSelectImage = { launcher.launch("image/*") },
            onRemoveBg = {
                imageUri?.let { uri ->
                    scope.launch {
                        val newUri = removeBackground(context, uri)
                        if (newUri != null) {
                            imageUri = newUri
                        }
                    }
                }
            },
            onClearMarkers = {},
            onLockMural = {},
            onResetMural = {},
            onSliderSelected = { activeSlider = it }
        )
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                CameraPreview(modifier = Modifier.fillMaxSize())

                imageUri?.let {
                    val painter = rememberAsyncImagePainter(it)
                    Image(
                        painter = painter,
                        contentDescription = "Selected Image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        alpha = opacity,
                        colorFilter = getColorFilter(saturation, brightness, contrast)
                    )
                }

                activeSlider?.let {
                    SliderPopup(
                        sliderType = it,
                        opacity = opacity,
                        contrast = contrast,
                        saturation = saturation,
                        brightness = brightness,
                        onOpacityChange = { opacity = it },
                        onContrastChange = { contrast = it },
                        onSaturationChange = { saturation = it },
                        onBrightnessChange = { brightness = it },
                        onDismiss = { activeSlider = null }
                    )
                }
            }
        }
    }
}

@Composable
fun SliderPopup(
    sliderType: SliderType,
    opacity: Float,
    contrast: Float,
    saturation: Float,
    brightness: Float,
    onOpacityChange: (Float) -> Unit,
    onContrastChange: (Float) -> Unit,
    onSaturationChange: (Float) -> Unit,
    onBrightnessChange: (Float) -> Unit,
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
                    SliderType.Opacity -> Slider(value = opacity, onValueChange = onOpacityChange)
                    SliderType.Contrast -> Slider(value = contrast, onValueChange = onContrastChange, valueRange = 0f..10f)
                    SliderType.Saturation -> Slider(value = saturation, onValueChange = onSaturationChange, valueRange = 0f..10f)
                    SliderType.Brightness -> Slider(value = brightness, onValueChange = onBrightnessChange, valueRange = -1f..1f)
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
