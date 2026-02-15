package com.hereliesaz.graffitixr.feature.ar

import android.graphics.PixelFormat
import android.util.Log
import android.opengl.GLSurfaceView
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
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.feature.ar.util.LightEstimationAnalyzer
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

    // State for estimated ambient light (0.0 - 1.0)
    var ambientLight by remember { mutableFloatStateOf(0.5f) }

    // Pre-create PreviewView to manage its lifecycle outside AndroidView update loop
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // Executor for image analysis (must be shut down)
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // We need to keep track of the camera provider to unbind on dispose
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            cameraProvider?.unbindAll()
            cameraExecutor.shutdown()
        }
    }

    LaunchedEffect(renderer) {
        onRendererCreated(renderer)
    }

    // Bind camera use cases only when permission changes or lifecycle changes
    LaunchedEffect(hasCameraPermission, lifecycleOwner) {
        if (hasCameraPermission) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    val provider = cameraProviderFuture.get()
                    cameraProvider = provider
                    bindCameraUseCases(
                        provider,
                        lifecycleOwner,
                        previewView,
                        cameraExecutor
                    ) { intensity ->
                        ambientLight = intensity
                    }
                } catch (e: Exception) {
                    Log.e("ArView", "Camera binding failed", e)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // --- LAYER 1: The Reality (Camera Preview) ---
        // We use PERFORMANCE mode to force a SurfaceView, which allows the
        // GLSurfaceView (Layer 2) to sit correctly on top with ZOrderMediaOverlay.
        if (hasCameraPermission) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )
        }

        // --- LAYER 2: The Hallucination (AR Graphics) ---
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(3)
                    setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                    holder.setFormat(PixelFormat.TRANSLUCENT)

                    // Places this surface ON TOP of the Camera SurfaceView,
                    // but BELOW the window (UI) layer.
                    setZOrderMediaOverlay(true)

                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }

    // Update renderer with real light estimation or flashlight override
    LaunchedEffect(ambientLight, uiState.isFlashlightOn) {
        val intensity = if (uiState.isFlashlightOn) 1.0f else ambientLight
        // Default white color for now
        renderer.updateLightEstimate(intensity, floatArrayOf(1f, 1f, 1f))
    }

    LaunchedEffect(activeLayer) {
        if (activeLayer != null) {
            renderer.setOverlay(activeLayer.bitmap)
        }
    }
}

private fun bindCameraUseCases(
    cameraProvider: ProcessCameraProvider,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    executor: ExecutorService,
    onLightUpdate: (Float) -> Unit
) {
    val preview = Preview.Builder().build()
    val selector = CameraSelector.DEFAULT_BACK_CAMERA
    val analysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
        .also {
            it.setAnalyzer(
                executor,
                LightEstimationAnalyzer(onLightUpdate)
            )
        }

    preview.setSurfaceProvider(previewView.surfaceProvider)

    try {
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            selector,
            preview,
            analysis
        )
    } catch (e: Exception) {
        Log.e("ArView", "Camera binding failed", e)
    }
}
