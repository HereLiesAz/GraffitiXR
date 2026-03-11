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

                    LaunchedEffect(arUiState.isFlashlightOn) {
                        rendererRef.value?.updateFlashlight(arUiState.isFlashlightOn)
                    }

                    // Composite visible layers into a bitmap for the GL overlay quad —
                    // but ONLY once an anchor has been established (fingerprint saved to project).
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

                        // Register the composited artwork as the teleological painting guide so
                        // the engine knows what features should appear when the mural is finished.
                        // This runs on the IO dispatcher inside addLayerFeaturesToSLAM.
                        arViewModel.updatePaintingGuide(composite)
                    }

                    AndroidView(
                        factory = { ctx ->
                            val renderer = ArRenderer(
                                context = ctx,
                                slamManager = slamManager,
                                onTargetCaptured = { bmp, cw, ch, depth, dw, dh, stride, intr, viewMat, rotation ->
                                    bmp?.let { rawBmp ->
                                        arViewModel.onTargetCaptured(
                                            rawBmp, depth,
                                            cw, ch,
                                            dw, dh, stride,
                                            intr, viewMat, rotation
                                        )
                                    }
                                },
                                onTrackingUpdated = { isTracking, splatCount, isDepthSupported ->
                                    arViewModel.setTrackingState(isTracking, splatCount, isDepthSupported)
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
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(isWaitingForTap) {
                                // Phase 4: In tap-target mode, forward normalized taps to ArViewModel
                                // so the app can capture a frame and run ORB detection on the mark.
                                if (isWaitingForTap) {
                                    detectTapGestures { offset ->
                                        val nx = offset.x / size.width
                                        val ny = offset.y / size.height
                                        arViewModel.onScreenTap(nx, ny)
                                    }
                                }
                            }
                    )

                    // Phase 4: Green-highlighted annotated bitmap overlay.
                    // Shown over the camera feed while in tap mode so the artist can see
                    // which features on their painted mark the app has recognized.
                    val annotated = arUiState.annotatedCaptureBitmap
                    if (isWaitingForTap && annotated != null) {
                        Image(
                            bitmap = annotated.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    // Green tint to signal "recognized" — overlaid semi-transparently
                                    alpha = 0.65f
                                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                                        Color(0x8800CC44),
                                        androidx.compose.ui.graphics.BlendMode.SrcAtop
                                    )
                                },
                            contentScale = ContentScale.Fit
                        )
                    }
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
                        if (!isTouchLocked && !isImageLocked && activeLayer != null && !isWaitingForTap) {
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
                // In AR mode, artwork is rendered by OverlayRenderer as a 3D-anchored
                // GL texture — not as flat 2D Compose overlays (which would look identical
                // to OVERLAY mode and aren't anchored to anything).
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
                } // end if (editorMode != AR)
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

/**
 * Composite all visible layers (those with bitmaps) into a single ARGB_8888 bitmap for the
 * AR anchor overlay.  Applies each layer's 2D transform (offset, scale, Z-rotation, opacity).
 * 3D perspective rotations (rotationX/rotationY) are intentionally ignored here — they are a
 * Compose-only visual effect and cannot be reproduced in a flat android.graphics.Canvas.
 *
 * The canvas size is taken from the first layer's bitmap dimensions and all subsequent layers
 * are drawn relative to that same coordinate space (center-aligned).
 */
private fun compositeLayersForAr(layers: List<Layer>): AndroidBitmap {
    val w = layers.firstNotNullOfOrNull { it.bitmap?.width } ?: 1024
    val h = layers.firstNotNullOfOrNull { it.bitmap?.height } ?: 1024
    val result = AndroidBitmap.createBitmap(w, h, AndroidBitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    for (layer in layers) {
        val bmp = layer.bitmap ?: continue
        paint.alpha = (layer.opacity.coerceIn(0f, 1f) * 255).toInt()
        val matrix = Matrix()
        // Pivot at bitmap center, then apply layer transform, then re-center on canvas
        matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
        matrix.postScale(layer.scale, layer.scale)
        matrix.postRotate(layer.rotationZ)
        matrix.postTranslate(w / 2f + layer.offset.x, h / 2f + layer.offset.y)
        canvas.drawBitmap(bmp, matrix, paint)
    }
    return result
}
