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

    DisposableEffect(Unit) {
        onDispose {
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
                                    }
                                )
                            )
                        }

                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)

                } catch (e: Exception) {
                    Log.e("ArView", "Camera binding failed", e)
                }
            }, ContextCompat.getMainExecutor(context))
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
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(3)
                    setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                    holder.setFormat(PixelFormat.TRANSLUCENT)
                    setZOrderMediaOverlay(true)
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }

    LaunchedEffect(ambientLight, uiState.isFlashlightOn) {
        val intensity = if (uiState.isFlashlightOn) 1.0f else ambientLight
        renderer.updateLightEstimate(intensity, floatArrayOf(1f, 1f, 1f))
    }

    LaunchedEffect(activeLayer) {
        if (activeLayer != null) {
            renderer.setOverlay(activeLayer.bitmap)
        }
    }
}