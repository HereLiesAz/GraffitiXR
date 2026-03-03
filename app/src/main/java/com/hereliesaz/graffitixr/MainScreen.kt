package com.hereliesaz.graffitixr

import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.ar.CameraPreview
import com.hereliesaz.graffitixr.feature.ar.rememberCameraController
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import kotlinx.coroutines.coroutineScope

@Composable
fun MainScreen(
    uiState: EditorUiState,
    arUiState: ArUiState,
    editorViewModel: EditorViewModel,
    arViewModel: ArViewModel,
    slamManager: SlamManager,
    hasCameraPermission: Boolean,
    onRendererCreated: (ArRenderer) -> Unit
) {
    val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }
    val isImageLocked = activeLayer?.isImageLocked ?: false
    val context = LocalContext.current
    val rendererRef = remember { mutableStateOf<ArRenderer?>(null) }

    // 1. Render Backgrounds (Camera or Mockup)
    if (hasCameraPermission) {
        when (uiState.editorMode) {
            EditorMode.AR -> {
                // Let the ViewModel manage the AR session lifecycle
                LaunchedEffect(Unit) {
                    arViewModel.setArMode(true, context)
                }

                // When this composable is disposed, notify the ViewModel to exit AR mode
                DisposableEffect(Unit) {
                    onDispose {
                        arViewModel.setArMode(false, context)
                    }
                }

                // Apply flashlight state when it changes
                LaunchedEffect(arUiState.isFlashlightOn) {
                    rendererRef.value?.updateFlashlight(arUiState.isFlashlightOn)
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
                            setZOrderMediaOverlay(true)
                            holder.setFormat(PixelFormat.TRANSLUCENT)
                            setRenderer(renderer)
                            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            EditorMode.OVERLAY -> {
                val controller = rememberCameraController()
                LaunchedEffect(arUiState.isFlashlightOn) {
                    controller.enableTorch(arUiState.isFlashlightOn)
                }
                CameraPreview(
                    controller = controller,
                    onPhotoCaptured = {},
                    onAnalyzerFrame = arViewModel::onCameraFrameForStereo,
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
                coroutineScope {
                    detectTransformGestures { _, pan, zoom, rotation ->
                        editorViewModel.onTransformGesture(pan, zoom, rotation)
                    }
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
