package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.opengl.Matrix
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.feature.ar.util.DualAnalyzer
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import java.util.concurrent.Executors

/**
 * ArView manages the camera lifecycle and the native Vulkan surface.
 * It provides the physical Surface to SlamManager for direct native rendering.
 */
@OptIn(ExperimentalGetImage::class)
@Composable
fun ArView(
    viewModel: ArViewModel,
    uiState: ArUiState,
    slamManager: SlamManager,
    projectRepository: ProjectRepository,
    activeLayer: Layer?,
    onRendererCreated: (ArRenderer) -> Unit,
    hasCameraPermission: Boolean
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val assetManager = context.assets

    val renderer = remember(slamManager) { ArRenderer(slamManager) }
    var ambientLight by remember { mutableFloatStateOf(0.5f) }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }

    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val listener = remember(renderer) {
        object : SensorEventListener {
            private val rotationMatrix = FloatArray(16)
            private val remappedMatrix = FloatArray(16)
            private val viewMatrix = FloatArray(16)

            override fun onSensorChanged(event: SensorEvent?) {
                event?.let {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, it.values)
                    val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
                    when(display.rotation) {
                        Surface.ROTATION_0 -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Y, remappedMatrix)
                        Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remappedMatrix)
                        Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remappedMatrix)
                        Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remappedMatrix)
                        else -> System.arraycopy(rotationMatrix, 0, remappedMatrix, 0, 16)
                    }
                    Matrix.transposeM(viewMatrix, 0, remappedMatrix, 0)
                    slamManager.updateCamera(viewMatrix, FloatArray(16).apply { Matrix.perspectiveM(this, 0, 60f, 1080f/2340f, 0.1f, 100f) })
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
    }

    // Native Surface Management
    val surfaceView = remember {
        SurfaceView(context).apply {
            holder.setFormat(PixelFormat.TRANSLUCENT)
            setZOrderMediaOverlay(true)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    slamManager.initVulkan(holder.surface, assetManager)
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    slamManager.resizeVulkan(width, height)
                    slamManager.onSurfaceChanged(width, height)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    slamManager.destroyVulkan()
                }
            })
        }
    }

    DisposableEffect(sensorManager, rotationSensor) {
        if (rotationSensor != null) {
            sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        }
        onDispose {
            sensorManager.unregisterListener(listener)
            cameraProvider?.unbindAll()
            cameraExecutor.shutdown()
        }
    }

    LaunchedEffect(hasCameraPermission, lifecycleOwner) {
        if (hasCameraPermission) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    val provider = cameraProviderFuture.get()
                    cameraProvider = provider
                    val preview = Preview.Builder().build()
                    val selector = CameraSelector.DEFAULT_BACK_CAMERA
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build()
                        .also {
                            it.setAnalyzer(cameraExecutor) { imageProxy ->
                                val image = imageProxy.image
                                if (image != null) {
                                    // Direct monocular feed to SLAM
                                    val buffer = image.planes[0].buffer
                                    slamManager.feedMonocularData(buffer, image.width, image.height)

                                    // Optional: Process for light estimation
                                    val yPlane = image.planes[0].buffer
                                    var sum = 0L
                                    val pixels = ByteArray(yPlane.remaining())
                                    yPlane.get(pixels)
                                    for (p in pixels) sum += (p.toInt() and 0xFF)
                                    ambientLight = (sum / pixels.size.toFloat()) / 255f
                                }
                                imageProxy.close()
                            }
                        }

                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    provider.unbindAll()
                    camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                } catch (e: Exception) {
                    Log.e("ArView", "Camera binding failed", e)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        }
        AndroidView(factory = { surfaceView }, modifier = Modifier.fillMaxSize())
    }

    LaunchedEffect(ambientLight, uiState.isFlashlightOn) {
        val intensity = if (uiState.isFlashlightOn) 1.0f else ambientLight
        slamManager.updateLight(intensity)
        camera?.cameraControl?.enableTorch(uiState.isFlashlightOn)
    }
}