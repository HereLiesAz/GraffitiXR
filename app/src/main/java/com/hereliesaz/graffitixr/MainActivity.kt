package com.hereliesaz.graffitixr

import android.Manifest
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.xr.compose.material3.Material
import androidx.xr.compose.material3.Model
import androidx.xr.compose.platform.XrScene
import coil.compose.rememberAsyncImagePainter
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.hereliesaz.graffitixr.ui.theme.GraffitiXRTheme
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Pose

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
                        val markers = remember { mutableStateListOf<Pose>() }
                        XrScene(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTapGestures { offset ->
                                        val hitResult = session?.hitTest(offset.x, offset.y)
                                        if (hitResult?.isNotEmpty() == true) {
                                            if (markers.size < 4) {
                                                markers.add(hitResult.first().hitPose)
                                            }
                                        }
                                    }
                                },
                            activity = this,
                        ) {
                            MainScreen(markers) { markers.clear() }
                        }
                    } else {
                        NonArScreen()
                    }
                } else {
                    Column {
                        Text("Camera permission is required to use this app.")
                        Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                            Text("Request permission")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MainScreen(markers: List<Pose>, onClearMarkers: () -> Unit) {
    var opacity by remember { mutableStateOf(1f) }
    var contrast by remember { mutableStateOf(1f) }
    var saturation by remember { mutableStateOf(1f) }
    var imageUri by remember { mutableStateOf<Uri?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        imageUri = uri
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0f)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            markers.forEach { markerPose ->
                Model(
                    "models/sphere.obj",
                    initialPose = markerPose,
                    scale = floatArrayOf(0.05f, 0.05f, 0.05f)
                ) {
                    Material(color = Color.Red)
                }
            }

            if (markers.size == 4) {
                imageUri?.let {
                    val painter = rememberAsyncImagePainter(it)
                    Mural(
                        markers = markers,
                        painter = painter,
                        opacity = opacity,
                        contrast = contrast,
                        saturation = saturation
                    )
                }
            }

            Controls(
                opacity = opacity,
                contrast = contrast,
                saturation = saturation,
                onOpacityChange = { opacity = it },
                onContrastChange = { contrast = it },
                onSaturationChange = { saturation = it },
                onSelectImage = { launcher.launch("image/*") },
                onClearMarkers = onClearMarkers
            )
        }
    }
}

@Composable
fun NonArScreen() {
    var opacity by remember { mutableStateOf(1f) }
    var contrast by remember { mutableStateOf(1f) }
    var saturation by remember { mutableStateOf(1f) }
    var imageUri by remember { mutableStateOf<Uri?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        imageUri = uri
    }

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
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                        ColorMatrix().apply {
                            setToSaturation(saturation)
                            postConcat(ColorMatrix(floatArrayOf(
                                contrast, 0f, 0f, 0f, 0f,
                                0f, contrast, 0f, 0f, 0f,
                                0f, 0f, contrast, 0f, 0f,
                                0f, 0f, 0f, 1f, 0f
                            )))
                        }
                    )
                )
            }

            Controls(
                opacity = opacity,
                contrast = contrast,
                saturation = saturation,
                onOpacityChange = { opacity = it },
                onContrastChange = { contrast = it },
                onSaturationChange = { saturation = it },
                onSelectImage = { launcher.launch("image/*") }
            )
        }
    }
}

@Composable
fun Controls(
    opacity: Float,
    contrast: Float,
    saturation: Float,
    onOpacityChange: (Float) -> Unit,
    onContrastChange: (Float) -> Unit,
    onSaturationChange: (Float) -> Unit,
    onSelectImage: () -> Unit,
    onClearMarkers: (() -> Unit)? = null
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Button(onClick = onSelectImage) {
            Text("Select Image")
        }
        onClearMarkers?.let {
            Button(onClick = it) {
                Text("Clear Markers")
            }
        }
        Text("Opacity")
        Slider(value = opacity, onValueChange = onOpacityChange)
        Text("Contrast")
        Slider(value = contrast, onValueChange = onContrastChange, valueRange = 0f..10f)
        Text("Saturation")
        Slider(value = saturation, onValueChange = onSaturationChange, valueRange = 0f..10f)
    }
}
