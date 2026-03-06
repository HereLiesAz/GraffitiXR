package com.hereliesaz.graffitixr

import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import androidx.camera.lifecycle.ProcessCameraProvider
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
import com.hereliesaz.graffitixr.feature.ar.CameraController
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
    cameraController: CameraController,
    onRendererCreated: (ArRenderer) -> Unit
) {
    val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }
    val isImageLocked = activeLayer?.isImageLocked ?: false
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val rendererRef = remember { mutableStateOf<ArRenderer?>(null) }

    if (uiState.editorMode == EditorMode.TRACE) {
        // True black background for trace mode to eliminate glare
        Spacer(modifier = Modifier.fillMaxSize().background(Color.Black))
    } else if (hasCameraPermission) {
        // Strict separation: Only mount the AR view if we are actually in AR mode.
        // This ensures the GLSurfaceView and CameraX PreviewView never exist at the same time.
        if (uiState.editorMode == EditorMode.AR) {
            var glView by remember { mutableStateOf<GLSurfaceView?>(null) }

            DisposableEffect(Unit) {
                arViewModel.setArMode(true, context)
                onDispose {
                    arViewModel.setArMode(false, context)
                }
            }

            DisposableEffect(lifecycleOwner, glView) {
                if (glView == null) return@DisposableEffect onDispose {}
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
                }
            }

            LaunchedEffect(arUiState.isFlashlightOn) {
                rendererRef.value?.updateFlashlight(arUiState.isFlashlightOn)
            }

            AndroidView(
                factory = { ctx ->
                    val renderer = ArRenderer(
                        context = ctx,
                        slamManager = slamManager,
                        isCaptureRequested = { arUiState.isCaptureRequested },
                        onTargetCaptured = { bmp, depth, w, h, int ->
                            arViewModel.onTargetCaptured(bmp, depth, w, h, int)
                        },
                        onTrackingUpdated = { isTracking, splatCount ->
                            arViewModel.setTrackingState(isTracking, splatCount)
                        }
                    )
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
        } else if (uiState.editorMode == EditorMode.OVERLAY) {
            LaunchedEffect(arUiState.isFlashlightOn) {
                cameraController.enableTorch(arUiState.isFlashlightOn)
            }

            // Guaranteed teardown for CameraX hardware locks
            DisposableEffect(Unit) {
                onDispose {
                    try {
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                        val provider = cameraProviderFuture.get()
                        provider.unbindAll()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            CameraPreview(
                controller = cameraController,
                onPhotoCaptured = { arViewModel.setTempCapture(it) },
                onAnalyzerFrame = arViewModel::onCameraFrameForStereo,
                onLightUpdate = arViewModel::updateLightLevel,
                modifier = Modifier.fillMaxSize(),
                arViewModel = arViewModel
            )
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

    Box(modifier = Modifier
        .fillMaxSize()
        .pointerInput(uiState.activeLayerId, isImageLocked, uiState.activeTool, isTouchLocked, "tap") {
            if (!isTouchLocked && !isImageLocked && activeLayer != null) {
                if (uiState.activeTool == Tool.NONE) {
                    detectTapGestures(onDoubleTap = { editorViewModel.onCycleRotationAxis() })
                }
            }
        }
        .pointerInput(uiState.activeLayerId, isImageLocked, uiState.activeTool, isTouchLocked, "transform") {
            if (!isTouchLocked && !isImageLocked && activeLayer != null) {
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
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
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

        if (!isTouchLocked && !isImageLocked && activeLayer != null) {
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
                    onPathFinished = { path, _, size ->
                        editorViewModel.onDrawingPathFinished(path, size)
                    }
                )
            }
        }
    }
}