package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import androidx.camera.core.CameraSelector
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.feature.ar.util.DualAnalyzer
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
                        Surface.ROTATION_0 -> {
                            SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Y, remappedMatrix)
                        }
                        Surface.ROTATION_90 -> {
                            SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remappedMatrix)
                        }
                        Surface.ROTATION_180 -> {
                            SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remappedMatrix)
                        }
                        Surface.ROTATION_270 -> {
                            SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remappedMatrix)
                        }
                        else -> System.arraycopy(rotationMatrix, 0, remappedMatrix, 0, 16)
                    }

                    // Convert Device->World to World->Device (View Matrix) by transposing
                    Matrix.transposeM(viewMatrix, 0, remappedMatrix, 0)
                    renderer.updateViewMatrix(viewMatrix)
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
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

    LaunchedEffect(renderer) {
        onRendererCreated(renderer)
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
                        .build()
                        .also {
                            // FIX: Utilize DualAnalyzer to handle both lighting and Teleological tracking
                            it.setAnalyzer(
                                cameraExecutor,
                                DualAnalyzer(
                                    onLightUpdate = { intensity -> ambientLight = intensity },
                                    onTeleologicalFrame = { bitmap ->
                                        // ViewMatrix is extracted inside the renderer typically, but here we just pass an identity 
                                        // or rely on the engine to fetch it since it's cached in MobileGS.
                                        val dummyViewMatrix = floatArrayOf(
                                            1f, 0f, 0f, 0f,
                                            0f, 1f, 0f, 0f,
                                            0f, 0f, 1f, 0f,
                                            0f, 0f, 0f, 1f
                                        )
                                        viewModel.processTeleologicalFrame(bitmap, dummyViewMatrix)
                                    },
                                    onSlamFrame = { buffer, width, height ->
                                        slamManager.feedMonocularData(buffer, width, height)
                                    }
                                )
                            )
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

    val glSurfaceView = remember {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            setZOrderMediaOverlay(true)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

    DisposableEffect(lifecycleOwner) {
        // Manage GLSurfaceView lifecycle to prevent context loss and black screens
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                glSurfaceView.onResume()
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                glSurfaceView.onPause()
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )
        }

        AndroidView(
            factory = { glSurfaceView },
            modifier = Modifier.fillMaxSize()
        )
    }

    LaunchedEffect(ambientLight, uiState.isFlashlightOn) {
        val intensity = if (uiState.isFlashlightOn) 1.0f else ambientLight
        renderer.updateLightEstimate(intensity, floatArrayOf(1f, 1f, 1f))
        camera?.cameraControl?.enableTorch(uiState.isFlashlightOn)
    }

    LaunchedEffect(activeLayer) {
        if (activeLayer != null) {
            renderer.setOverlay(activeLayer.bitmap)
        }
    }
}
