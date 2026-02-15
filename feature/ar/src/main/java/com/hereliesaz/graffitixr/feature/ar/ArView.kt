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
import com.hereliesaz.graffitixr.nativebridge.SlamManager
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

    LaunchedEffect(renderer) {
        onRendererCreated(renderer)
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // --- LAYER 1: The Reality (Camera Preview) ---
        // We use PERFORMANCE mode to force a SurfaceView, which allows the
        // GLSurfaceView (Layer 2) to sit correctly on top with ZOrderMediaOverlay.
        if (hasCameraPermission) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { previewView ->
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            bindCameraUseCases(
                                cameraProvider,
                                lifecycleOwner,
                                previewView,
                                slamManager
                            )
                        } catch (e: Exception) {
                            Log.e("ArView", "Camera binding failed", e)
                        }
                    }, ContextCompat.getMainExecutor(context))
                }
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

    LaunchedEffect(uiState.isFlashlightOn) {
        renderer.updateLightEstimate(if (uiState.isFlashlightOn) 1.0f else 0.5f)
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
    val preview = Preview.Builder().build()
    val selector = CameraSelector.DEFAULT_BACK_CAMERA
    val analysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
        .also {
            it.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                imageProxy.close()
            }
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