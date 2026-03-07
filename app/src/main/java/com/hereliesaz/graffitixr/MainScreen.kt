// FILE: app/src/main/java/com/hereliesaz/graffitixr/MainScreen.kt
package com.hereliesaz.graffitixr

import android.graphics.Matrix
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.ar.CameraPreview
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
    isCameraActive: Boolean,
    editorViewModel: EditorViewModel,
    arViewModel: ArViewModel,
    slamManager: SlamManager,
    hasCameraPermission: Boolean,
    cameraController: androidx.camera.view.LifecycleCameraController,
    onRendererCreated: (ArRenderer) -> Unit
) {
    val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }
    val isImageLocked = activeLayer?.isImageLocked ?: false
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val rendererRef = remember { mutableStateOf<ArRenderer?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        if (hasCameraPermission && isCameraActive && uiState.editorMode != EditorMode.TRACE) {
            when (uiState.editorMode) {
                EditorMode.AR -> {
                    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }

                    DisposableEffect(uiState.editorMode) {
                        arViewModel.setArMode(true, context)
                        onDispose {
                            arViewModel.setArMode(false, context)
                        }
                    }

                    DisposableEffect(lifecycleOwner, glView) {
                        if (glView == null) return@DisposableEffect onDispose {}
                        val observer = LifecycleEventObserver { _, event ->
                            when (event) {
                                Lifecycle.Event.ON_RESUME -> {
                                    arViewModel.onActivityResumed()
                                    glView?.onResume()
                                }
                                Lifecycle.Event.ON_PAUSE -> {
                                    glView?.onPause()
                                    arViewModel.onActivityPaused()
                                }
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
                                onTargetCaptured = { bmp, depth, w, h, int ->
                                    bmp?.let { rawBitmap ->
                                        val matrix = Matrix().apply { postRotate(90f) }
                                        val rotatedBmp = android.graphics.Bitmap.createBitmap(
                                            rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true
                                        )
                                        arViewModel.onTargetCaptured(rotatedBmp, depth, rotatedBmp.width, rotatedBmp.height, int)
                                    }
                                },
                                onTrackingUpdated = { isTracking, splatCount ->
                                    arViewModel.setTrackingState(isTracking, splatCount)
                                },
                                onLightUpdated = { level ->
                                    arViewModel.updateLightLevel(level)
                                    slamManager.updateLightLevel(level)
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
                        update = { view ->
                            rendererRef.value?.captureRequested = arUiState.isCaptureRequested
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                EditorMode.OVERLAY -> {
                    LaunchedEffect(arUiState.isFlashlightOn) {
                        cameraController.enableTorch(arUiState.isFlashlightOn)
                    }

                    CameraPreview(
                        controller = cameraController,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                EditorMode.MOCKUP, EditorMode.TRACE -> {}
            }

            uiState.backgroundBitmap?.takeIf { uiState.editorMode == EditorMode.MOCKUP }
                ?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Background Mockup",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(
                        uiState.activeLayerId,
                        isImageLocked,
                        uiState.activeTool,
                        isTouchLocked,
                        "tap"
                    ) {
                        if (!isTouchLocked && !isImageLocked && activeLayer != null) {
                            if (uiState.activeTool == Tool.NONE) {
                                detectTapGestures(onDoubleTap = { editorViewModel.onCycleRotationAxis() })
                            }
                        }
                    }
                    .pointerInput(
                        uiState.activeLayerId,
                        isImageLocked,
                        uiState.activeTool,
                        isTouchLocked,
                        "transform"
                    ) {
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
                                    compositingStrategy =
                                        androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                                },
                            contentScale = ContentScale.Fit
                        )
                    }
                }
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