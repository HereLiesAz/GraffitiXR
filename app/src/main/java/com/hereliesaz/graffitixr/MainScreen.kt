package com.hereliesaz.graffitixr

import android.opengl.GLSurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                CameraPreview(
                    controller = rememberCameraController(),
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
                    rotate(layer.rotationZ) // Basic Z rotation for 2D canvas fallback
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

    // AR debug overlay — shows live tracking state for on-device diagnostics.
    if (uiState.editorMode == EditorMode.AR) {
        val chipColor = when (arUiState.trackingState) {
            "TRACKING"     -> Color(0xCC1B5E20)  // dark green
            "PAUSED"       -> Color(0xCCE65100)  // orange
            else           -> Color(0xCC424242)  // grey (initializing / stopped)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 12.dp, start = 12.dp),
            contentAlignment = Alignment.TopStart
        ) {
            Text(
                text = arUiState.trackingState,
                color = Color.White,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(chipColor)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
