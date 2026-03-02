package com.hereliesaz.graffitixr

import android.opengl.GLSurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.hereliesaz.graffitixr.nativebridge.SlamManager

/**
 * Main operational screen managing the AR viewport, capture workflows, and the digital canvas.
 * Stripped of the dual-render pipeline to preserve battery life and eliminate compositor conflicts.
 */
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

/**
 * The unified AR rendering viewport. No more surface z-ordering turf wars.
 * ARCore owns the camera, GLSurfaceView owns the paint.
 */
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val rendererRef = remember { mutableStateOf<ArRenderer?>(null) }

    // 1. Render Backgrounds (Camera or Mockup)
    if (hasCameraPermission) {
        when (uiState.editorMode) {
            EditorMode.AR -> {
                // AR mode: ARCore owns the camera. GLSurfaceView renders the camera feed
                // via BackgroundRenderer and feeds frames to the SLAM engine.
                // Key on editorMode so this re-fires when returning to AR from another mode.
                DisposableEffect(uiState.editorMode) {
                    arViewModel.initArSession(context)
                    arViewModel.resumeArSession()
                    rendererRef.value?.let { arViewModel.attachSessionToRenderer(it) }
                    onDispose {
                        arViewModel.pauseArSession()
                        arViewModel.attachSessionToRenderer(null)
                    }
                }
                AndroidView(
                    factory = { ctx ->
                        val renderer = ArRenderer(ctx, slamManager) { isTracking ->
                            arViewModel.setTrackingState(isTracking)
                        }
                        rendererRef.value = renderer
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
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    editorViewModel.onGestureStart()
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                    editorViewModel.onGestureEnd()
                }
            }
        }
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
