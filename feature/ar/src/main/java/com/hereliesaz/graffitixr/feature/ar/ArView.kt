package com.hereliesaz.graffitixr.feature.ar

import android.graphics.PixelFormat
import android.util.Log
import android.view.Surface
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
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import java.util.concurrent.Executors
import android.opengl.GLSurfaceView

@Composable
fun ArView(
    viewModel: ArViewModel,
    uiState: ArUiState,
    slamManager: SlamManager,
    projectRepository: ProjectRepository,
    activeLayer: Layer?,
    onRendererCreated: (ArRenderer) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 1. Initialize the Renderer
    val renderer = remember(slamManager) { ArRenderer(slamManager) }

    LaunchedEffect(renderer) {
        onRendererCreated(renderer)
    }

    // 2. State to hold the camera provider
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // 3. Request CameraProvider on launch
    LaunchedEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
        }, ContextCompat.getMainExecutor(context))
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // --- LAYER 1: The Reality (Camera Preview) ---
        // This MUST be behind the GLSurfaceView
        if (cameraProvider != null) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { previewView ->
                    bindCameraUseCases(
                        cameraProvider!!,
                        lifecycleOwner,
                        previewView,
                        slamManager
                    )
                }
            )
        }

        // --- LAYER 2: The Hallucination (AR Graphics) ---
        // We set this to transparent so the camera shows through
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(3)
                    setEGLConfigChooser(8, 8, 8, 8, 16, 0) // Alpha channel required
                    holder.setFormat(PixelFormat.TRANSLUCENT) // Make background transparent
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    setZOrderOnTop(true) // Ensure it sits physically on top of the SurfaceView
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }

    // Effect Updates
    LaunchedEffect(uiState.isFlashlightOn) {
        renderer.updateLightEstimate(if (uiState.isFlashlightOn) 1.0f else 0.5f)
    }

    LaunchedEffect(uiState.showPointCloud) {
        slamManager.setVisualizationMode(if (uiState.showPointCloud) 1 else 0)
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
    slamManager: SlamManager
) {
    // 1. Preview: The visual feed for the user
    val preview = Preview.Builder()
        .build()
        .also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

    // 2. Analysis: The data feed for the Native Engine
    val imageAnalysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
        .also {
            it.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                // Feed the image to the native engine for tracking
                // Note: slamManager.feedDepthData expects an android.media.Image
                imageProxy.image?.let { image ->
                    // slamManager.feedDepthData(image) // Uncomment if your native side is ready
                }
                imageProxy.close() // CRITICAL: Must close to get next frame
            }
        }

    // 3. Bind to Lifecycle
    try {
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageAnalysis
        )
    } catch (exc: Exception) {
        Log.e("ArView", "Use case binding failed", exc)
    }
}