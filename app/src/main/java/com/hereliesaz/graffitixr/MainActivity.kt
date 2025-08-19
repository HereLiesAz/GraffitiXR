package com.hereliesaz.graffitixr

import android.Manifest
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.xr.compose.platform.TrackedPlane
import androidx.xr.compose.platform.XrScene
import androidx.xr.compose.spatial.SpatialImage
import androidx.xr.arcore.rememberTrackedPlanes
import coil.compose.rememberAsyncImagePainter
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.hereliesaz.graffitixr.ui.theme.GraffitiXRTheme

@OptIn(ExperimentalPermissionsApi::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GraffitiXRTheme {
                val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
                if (cameraPermissionState.status.isGranted) {
                    XrScene(
                        modifier = Modifier.fillMaxSize(),
                        activity = this,
                    ) {
                        MainScreen()
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
fun MainScreen() {
    var opacity by remember { mutableStateOf(1f) }
    var contrast by remember { mutableStateOf(1f) }
    var saturation by remember { mutableStateOf(1f) }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var placedMural by remember { mutableStateOf<TrackedPlane?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        imageUri = uri
    }

    val planes = rememberTrackedPlanes()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0f)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            if (placedMural == null) {
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
                                postConcat(
                                    ColorMatrix(floatArrayOf(
                                    contrast, 0f, 0f, 0f, 0f,
                                    0f, contrast, 0f, 0f, 0f,
                                    0f, 0f, contrast, 0f, 0f,
                                    0f, 0f, 0f, 1f, 0f
                                ))
                                )
                            }
                        )
                    )
                }
            } else {
                imageUri?.let {
                    val painter = rememberAsyncImagePainter(it)
                    SpatialImage(
                        painter = painter,
                        contentDescription = "Placed Mural",
                        initialPose = placedMural!!.centerPose,
                        modifier = Modifier.fillMaxSize(),
                        alpha = opacity,
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                            ColorMatrix().apply {
                                setToSaturation(saturation)
                                postConcat(
                                    ColorMatrix(floatArrayOf(
                                    contrast, 0f, 0f, 0f, 0f,
                                    0f, contrast, 0f, 0f, 0f,
                                    0f, 0f, contrast, 0f, 0f,
                                    0f, 0f, 0f, 1f, 0f
                                ))
                                )
                            }
                        )
                    )
                }
            }


            Column(modifier = Modifier.padding(16.dp)) {
                Button(onClick = { launcher.launch("image/*") }) {
                    Text("Select Image")
                }
                Button(onClick = {
                    placedMural = planes.firstOrNull { it.alignment == TrackedPlane.Alignment.HORIZONTAL }
                }) {
                    Text("Place Mural")
                }
                Text("Opacity")
                Slider(value = opacity, onValueChange = { opacity = it })
                Text("Contrast")
                Slider(value = contrast, onValueChange = { contrast = it }, valueRange = 0f..10f)
                Text("Saturation")
                Slider(value = saturation, onValueChange = { saturation = it }, valueRange = 0f..10f)
            }
        }
    }
}
