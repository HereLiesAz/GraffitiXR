package com.hereliesaz.muraloverlay

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix
import android.graphics.ColorMatrix as AndroidColorMatrix
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import android.os.Build
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import com.hereliesaz.MuralOverlay.ui.theme.MuralOverlayTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!isCameraPermissionGranted()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        }

        setContent {
            MuralOverlayTheme {
                MuralOverlayApp()
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

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MuralOverlayApp() {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val storagePermissionState = rememberPermissionState(storagePermission)

    var imageUri by remember { mutableStateOf<Uri?>(null) }
    val launcher = rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: Uri? ->
        imageUri = uri
    }

    LaunchedEffect(Unit) {
        cameraPermissionState.launchPermissionRequest()
        storagePermissionState.launchPermissionRequest()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (cameraPermissionState.status == com.google.accompanist.permissions.PermissionStatus.Granted) {
            CameraView(imageUri) {
                launcher.launch("image/*")
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
fun CameraView(imageUri: Uri?, onSelectImage: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var opacity by remember { mutableStateOf(0.5f) }
    var saturation by remember { mutableStateOf(1f) }
    var contrast by remember { mutableStateOf(1f) }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val previewView = remember { PreviewView(context) }
    val imageCapture = remember { ImageCapture.Builder().build() }

    LaunchedEffect(cameraProviderFuture) {
        val cameraProvider = cameraProviderFuture.get()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
        } catch (exc: Exception) {
            android.util.Log.e("CameraView", "Use case binding failed", exc)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView({ previewView }, modifier = Modifier.fillMaxSize())

        imageUri?.let {
            Image(
                painter = rememberAsyncImagePainter(it),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                alpha = opacity,
                colorFilter = run {
                    val saturationMatrix = AndroidColorMatrix().apply { setSaturation(saturation) }
                    val contrastMatrix = AndroidColorMatrix(floatArrayOf(
                        contrast, 0f, 0f, 0f, (1f - contrast) * 128f,
                        0f, contrast, 0f, 0f, (1f - contrast) * 128f,
                        0f, 0f, contrast, 0f, (1f - contrast) * 128f,
                        0f, 0f, 0f, 1f, 0f
                    ))
                    saturationMatrix.postConcat(contrastMatrix)
                    ColorFilter.colorMatrix(ComposeColorMatrix(saturationMatrix.array))
                }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        ) {
            Button(onClick = onSelectImage, modifier = Modifier.fillMaxWidth()) {
                Text("Select Image")
            }
            Spacer(modifier = Modifier.height(16.dp))
            SliderRow("Opacity", opacity, { opacity = it })
            SliderRow("Saturation", saturation, { saturation = it }, valueRange = 0f..2f)
            SliderRow("Contrast", contrast, { contrast = it }, valueRange = 0f..2f)
        }
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
fun SliderRow(label: String, value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float> = 0f..1f) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.width(16.dp))
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
            modifier = Modifier.weight(1f)
            modifier = Modifier.fillMaxWidth()
        )
    }
}
