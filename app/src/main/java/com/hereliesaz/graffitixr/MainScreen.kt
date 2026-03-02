package com.hereliesaz.graffitixr

import android.opengl.GLSurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import com.hereliesaz.graffitixr.common.model.CaptureStep
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.ar.CameraPreview
import com.hereliesaz.graffitixr.feature.ar.TargetCreationBackground
import com.hereliesaz.graffitixr.feature.ar.rememberCameraController
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import com.hereliesaz.graffitixr.feature.editor.GsViewer
import com.hereliesaz.graffitixr.nativebridge.SlamManager

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    editorViewModel: EditorViewModel,
    arViewModel: ArViewModel,
    slamManager: SlamManager,
    projectRepository: ProjectRepository,
    onRendererCreated: (ArRenderer) -> Unit,
    hasCameraPermission: Boolean,
    modifier: Modifier = Modifier
) {
    val uiState by editorViewModel.uiState.collectAsState()
    val mainUiState by viewModel.uiState.collectAsState()
    val arUiState by arViewModel.uiState.collectAsState()

    val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }

    Box(modifier = modifier.fillMaxSize()) {
        if (mainUiState.isCapturingTarget) {
            TargetCreationBackground(
                uiState = arUiState,
                captureStep = mainUiState.captureStep,
                onPhotoCaptured = { arViewModel.setTempCapture(it) },
                onCaptureConsumed = {
                    arViewModel.onCaptureConsumed()
                    viewModel.setCaptureStep(CaptureStep.RECTIFY)
                },
                onInitUnwarpPoints = { arViewModel.setUnwarpPoints(it) }
            )
        } else {
            ArViewport(
                uiState = uiState,
                editorViewModel = editorViewModel,
                arViewModel = arViewModel,
                slamManager = slamManager,
                activeLayer = activeLayer,
                hasCameraPermission = hasCameraPermission,
                onRendererCreated = onRendererCreated
            )
        }
    }
}

@Composable
fun ArViewport(
    uiState: EditorUiState,
    editorViewModel: EditorViewModel,
    arViewModel: ArViewModel,
    slamManager: SlamManager,
    activeLayer: Layer?,
    hasCameraPermission: Boolean,
    onRendererCreated: (ArRenderer) -> Unit
) {
    val isImageLocked = activeLayer?.isImageLocked ?: false
    val arUiState by arViewModel.uiState.collectAsState()

    // 1. Render Backgrounds (Camera or Mockup)
    if (hasCameraPermission) {
        when (uiState.editorMode) {
            EditorMode.AR -> {
                // AR mode: ARCore owns the camera. GLSurfaceView renders the camera feed
                // via BackgroundRenderer and feeds frames to the SLAM engine.
                DisposableEffect(Unit) {
                    arViewModel.resumeArSession()
                    onDispose { arViewModel.pauseArSession() }
                }
                AndroidView(
                    factory = { ctx ->
                        val renderer = ArRenderer(ctx, slamManager) { state, count ->
                            arViewModel.updateTrackingState(state, count)
                        }
                        arViewModel.attachSessionToRenderer(renderer)
                        onRendererCreated(renderer)
                        GLSurfaceView(ctx).apply {
                            setEGLContextClientVersion(3)
                            setRenderer(renderer)
                            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                // Vulkan SLAM overlay on top of the ARCore camera background.
                AndroidView(
                    factory = { ctx ->
                        GsViewer(ctx).apply {
                            setZOrderMediaOverlay(true)
                            holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            EditorMode.OVERLAY -> {
                // Overlay mode: CameraX preview, no ARCore.
                // Sync flashlight state → CameraX CameraControl (reliable torch when CameraX owns camera).
                val controller = rememberCameraController()
                LaunchedEffect(arUiState.isFlashlightOn) {
                    controller.enableTorch(arUiState.isFlashlightOn)
                }
                CameraPreview(
                    controller = controller,
                    onPhotoCaptured = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
            else -> {}
        }
    }

    uiState.backgroundBitmap?.takeIf { uiState.editorMode == EditorMode.MOCKUP }?.let { bmp ->
        androidx.compose.foundation.Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Background Mockup",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }

    // 2. Render Layers & Handle Gestures
    Canvas(modifier = Modifier
        .fillMaxSize()
        .pointerInput(uiState.activeLayerId, isImageLocked) {
            if (!isImageLocked && activeLayer != null && uiState.editorMode != EditorMode.TRACE) {
                detectTransformGestures { _, pan, zoom, rotation ->
                    editorViewModel.onTransformGesture(pan, zoom, rotation)
                }
            }
        }
    ) {
        uiState.layers.filter { it.isVisible }.forEach { layer ->
            layer.bitmap?.let { bmp ->
                withTransform({
                    translate(layer.offset.x, layer.offset.y)
                    scale(layer.scale, layer.scale)
                    rotate(layer.rotationZ)
                }) {
                    drawImage(
                        image = bmp.asImageBitmap(),
                        alpha = layer.opacity,
                        blendMode = layer.blendMode
                    )
                }
            }
        }
    }
}
