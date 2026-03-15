// FILE: app/src/main/java/com/hereliesaz/graffitixr/MainScreen.kt
package com.hereliesaz.graffitixr

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
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
import com.hereliesaz.graffitixr.common.model.Layer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import android.graphics.Bitmap as AndroidBitmap
import androidx.core.graphics.createBitmap

/**
 * The main stage where reality is augmented and screen touches are translated into either
 * teleological AR tracking actions or 2D canvas manipulations, depending on which illusion
 * the user is currently buying into.
 */
@Composable
fun MainScreen(
    uiState: EditorUiState,
    arUiState: ArUiState,
    isTouchLocked: Boolean,
    isCameraActive: Boolean,
    isWaitingForTap: Boolean,
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

                    // ADDED rendererRef.value to key to catch late initialization
                    LaunchedEffect(arUiState.isFlashlightOn, rendererRef.value) {
                        rendererRef.value?.updateFlashlight(arUiState.isFlashlightOn)
                    }

                    val visibleLayers = uiState.layers.filter { it.isVisible && it.bitmap != null }
                    LaunchedEffect(visibleLayers, arUiState.isAnchorEstablished) {
                        if (!arUiState.isAnchorEstablished || visibleLayers.isEmpty()) {
                            rendererRef.value?.updateOverlayBitmap(null)
                            return@LaunchedEffect
                        }
                        val composite = withContext(Dispatchers.Default) {
                            compositeLayersForAr(visibleLayers)
                        }
                        rendererRef.value?.updateOverlayBitmap(composite)
                        arViewModel.updatePaintingGuide(composite)
                    }

                    AndroidView(
                        factory = { ctx ->
                            val renderer = ArRenderer(
                                context = ctx,
                                slamManager = slamManager,
                                onTargetCaptured = { bmp, cw, ch, depth, dw, dh, stride, intr, viewMat, _ ->
                                    bmp?.let { rawBmp ->
                                        // The camera sensor provides horizontal raw data natively.
                                        // A 90-degree clockwise turn is structurally demanded for portrait orientation.
                                        val correctedRotation = if (rawBmp.width > rawBmp.height) 90 else 0
                                        arViewModel.onTargetCaptured(
                                            rawBmp, depth,
                                            cw, ch,
                                            dw, dh, stride,
                                            intr, viewMat, correctedRotation
                                        )
                                    }
                                },
                                onTrackingUpdated = { isTracking, splatCount, isDepthSupported, yaw ->
                                    arViewModel.setTrackingState(isTracking, splatCount, isDepthSupported, yaw)
                                },
                                onLightUpdated = { level ->
                                    arViewModel.updateLightLevel(level)
                                    slamManager.updateLightLevel(level)
                                },
                                onDiag = { text ->
                                    arViewModel.appendDiag(text)
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
                        isWaitingForTap,
                        "tap"
                    ) {
                        if (isWaitingForTap) {
                            detectTapGestures { offset ->
                                val nx = offset.x / size.width
                                val ny = offset.y / size.height
                                arViewModel.onScreenTap(nx, ny)
                            }
                        } else if (!isTouchLocked && !isImageLocked && activeLayer != null) {
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
                        isWaitingForTap,
                        "transform"
                    ) {
                        if (!isTouchLocked && !isImageLocked && activeLayer != null && !isWaitingForTap) {
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
                if (uiState.editorMode != EditorMode.AR) {
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

// Fixed canvas size for the AR overlay composite. Large enough that layers can be freely
// positioned and scaled without hitting canvas edges. Matches the physical quad size so
// the pixel density is ~1mm/px at a typical arm's-reach capture distance.
private const val COMPOSITE_CANVAS_SIZE = 2048

private fun compositeLayersForAr(layers: List<Layer>): AndroidBitmap {
    val w = COMPOSITE_CANVAS_SIZE
    val h = COMPOSITE_CANVAS_SIZE
    val result = createBitmap(w, h, AndroidBitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    for (layer in layers) {
        val bmp = layer.bitmap ?: continue
        paint.alpha = (layer.opacity.coerceIn(0f, 1f) * 255).toInt()
        val matrix = Matrix()
        matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
        matrix.postScale(layer.scale, layer.scale)
        matrix.postRotate(layer.rotationZ)
        matrix.postTranslate(w / 2f + layer.offset.x, h / 2f + layer.offset.y)
        canvas.drawBitmap(bmp, matrix, paint)
    }
    return result
}