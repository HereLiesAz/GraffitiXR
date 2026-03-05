// FILE: app/src/main/java/com/hereliesaz/graffitixr/MainScreen.kt
package com.hereliesaz.graffitixr

import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.ar.CameraPreview
import com.hereliesaz.graffitixr.feature.ar.rememberCameraController
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.feature.editor.DrawingCanvas
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import kotlinx.coroutines.coroutineScope

@Composable
fun MainScreen(
    uiState: EditorUiState,
    arUiState: ArUiState,
    isTouchLocked: Boolean,
    editorViewModel: EditorViewModel,
    arViewModel: ArViewModel,
    slamManager: SlamManager,
    hasCameraPermission: Boolean,
    onRendererCreated: (ArRenderer) -> Unit
) {
    val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }
    val isImageLocked = activeLayer?.isImageLocked ?: false
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val rendererRef = remember { mutableStateOf<ArRenderer?>(null) }

    // 1. Render Backgrounds (Camera, Trace, or Mockup)
    if (uiState.editorMode == EditorMode.TRACE) {
        Spacer(
            modifier = Modifier.fillMaxSize().background(Color.White)
        )
    } else if (hasCameraPermission) {
        when (uiState.editorMode) {
            EditorMode.AR -> {
                var glView by remember { mutableStateOf<GLSurfaceView?>(null) }

                LaunchedEffect(Unit) {
                    arViewModel.setArMode(true, context)
                }

                // Sync GLSurfaceView lifecycle with Activity lifecycle
                DisposableEffect(lifecycleOwner, glView) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> glView?.onResume()
                            Lifecycle.Event.ON_PAUSE -> glView?.onPause()
                            else -> {}
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                        glView?.onPause()
                        arViewModel.setArMode(false, context)
                    }
                }

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
                        val view = GLSurfaceView(ctx).apply {
                            setEGLContextClientVersion(3)
                            setZOrderMediaOverlay(true)
                            holder.setFormat(PixelFormat.TRANSLUCENT)
                            setRenderer(renderer)
                            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                        }
                        glView = view
                        view
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
                    onLightUpdate = arViewModel::updateLightLevel,
                    modifier = Modifier.fillMaxSize(),
                    arViewModel = arViewModel
                )
            }
            else -> {}
        }
    }

    uiState.backgroundBitmap?.takeIf { uiState.editorMode == EditorMode.MOCKUP }?.let { bmp ->
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Background Mockup",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }

    // 2. Render Layers & Handle Gestures
    Box(modifier = Modifier
        .fillMaxSize()
        .pointerInput(uiState.activeLayerId, isImageLocked, uiState.activeTool, isTouchLocked, "tap") {
            if (!isTouchLocked && !isImageLocked && activeLayer != null) {
                // Double tap cycles the 3D rotation axis
                if (uiState.activeTool == Tool.NONE) {
                    detectTapGestures(
                        onDoubleTap = {
                            editorViewModel.onCycleRotationAxis()
                        }
                    )
                }
            }
        }
        .pointerInput(uiState.activeLayerId, isImageLocked, uiState.activeTool, isTouchLocked, "transform") {
            if (!isTouchLocked && !isImageLocked && activeLayer != null) {
                // If Tool.NONE is explicitly selected, the user intends to pinch/pan/rotate the image
                if (uiState.activeTool == Tool.NONE) {
                    coroutineScope {
                        detectTransformGestures { _, pan, zoom, rotation ->
                            editorViewModel.onTransformGesture(pan, zoom, rotation)
                        }
                    }
                }
            }
        }
    ) {
        uiState.layers.filter { it.isVisible }.forEach { layer ->
            layer.bitmap?.let { bmp ->
                // Image layer utilizing graphicsLayer for 3D center pivots
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize() // Forces TransformOrigin.Center to align with screen center natively
                        .graphicsLayer {
                            translationX = layer.offset.x
                            translationY = layer.offset.y
                            scaleX = layer.scale
                            scaleY = layer.scale
                            rotationX = layer.rotationX
                            rotationY = layer.rotationY
                            rotationZ = layer.rotationZ
                            alpha = layer.opacity
                            transformOrigin = TransformOrigin.Center
                            blendMode = layer.blendMode
                            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                        },
                    contentScale = ContentScale.Fit
                )
            }
        }

        // 3. Render Active Stroke (DrawingCanvas)
        if (!isTouchLocked && !isImageLocked && activeLayer != null) {
            // If an active tool is selected, the DrawingCanvas overlay intercepts strokes.
            // Graphics layer explicitly maps drawing events to the 3D transformed space.
            if (uiState.activeTool != Tool.NONE) {
                DrawingCanvas(
                    activeTool = uiState.activeTool,
                    brushSize = uiState.brushSize,
                    activeColor = uiState.activeColor,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = activeLayer.offset.x
                            translationY = activeLayer.offset.y
                            scaleX = activeLayer.scale
                            scaleY = activeLayer.scale
                            rotationX = activeLayer.rotationX
                            rotationY = activeLayer.rotationY
                            rotationZ = activeLayer.rotationZ
                            transformOrigin = TransformOrigin.Center
                        },
                    onPathFinished = { path, _ ->
                        editorViewModel.onDrawingPathFinished(path)
                    }
                )
            }
        }
    }
}