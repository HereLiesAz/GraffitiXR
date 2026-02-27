package com.hereliesaz.graffitixr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
                onCaptureConsumed = { viewModel.setCaptureStep(CaptureStep.RECTIFY) },
                onInitUnwarpPoints = { arViewModel.setUnwarpPoints(it) }
            )
        } else {
            ArViewport(
                uiState = uiState,
                editorViewModel = editorViewModel,
                slamManager = slamManager,
                activeLayer = activeLayer,
                hasCameraPermission = hasCameraPermission
            )
        }
    }
}

@Composable
fun ArViewport(
    uiState: EditorUiState,
    editorViewModel: EditorViewModel,
    slamManager: SlamManager,
    activeLayer: Layer?,
    hasCameraPermission: Boolean
) {
    val isImageLocked = activeLayer?.isImageLocked ?: false

    // 1. Render Backgrounds (Camera or Mockup)
    if (hasCameraPermission && (uiState.editorMode == EditorMode.AR || uiState.editorMode == EditorMode.OVERLAY)) {
        CameraPreview(
            controller = rememberCameraController(),
            onPhotoCaptured = {},
            modifier = Modifier.fillMaxSize()
        )
    }

    if (uiState.editorMode == EditorMode.AR) {
        val context = LocalContext.current
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

    if (uiState.editorMode == EditorMode.MOCKUP && uiState.backgroundBitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = uiState.backgroundBitmap!!.asImageBitmap(),
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
}
