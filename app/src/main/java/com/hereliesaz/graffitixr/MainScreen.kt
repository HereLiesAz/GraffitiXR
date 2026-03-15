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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.hereliesaz.graffitixr.feature.editor.createColorMatrix
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

@Composable
fun MainScreen(
    uiState: EditorUiState,
    arUiState: ArUiState,
    isTouchLocked: Boolean,
    isCameraActive: Boolean,
    isWaitingForTap: Boolean,
    mainUiState: MainUiState,
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

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> arViewModel.onActivityResumed()
                    Lifecycle.Event.ON_PAUSE -> arViewModel.onActivityPaused()
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

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

                    LaunchedEffect(arUiState.isFlashlightOn, rendererRef.value) {
                        rendererRef.value?.updateFlashlight(arUiState.isFlashlightOn)
                    }

                    val visibleLayers = uiState.layers.filter { it.isVisible && it.bitmap != null }
                    val showPlaneConfirm = mainUiState.planeConfirmationPending

                    LaunchedEffect(visibleLayers, arUiState.isAnchorEstablished, showPlaneConfirm) {
                        if (!arUiState.isAnchorEstablished) {
                            rendererRef.value?.updateOverlayBitmap(null)
                            return@LaunchedEffect
                        }

                        if (showPlaneConfirm || visibleLayers.isEmpty()) {
                            val w = COMPOSITE_CANVAS_SIZE
                            val h = COMPOSITE_CANVAS_SIZE
                            val orangeBmp = createBitmap(w, h, AndroidBitmap.Config.ARGB_8888)
                            val canvas = Canvas(orangeBmp)

                            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = android.graphics.Color.rgb(255, 102, 0)
                                alpha = 180
                                style = Paint.Style.FILL
                            }
                            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = android.graphics.Color.WHITE
                                strokeWidth = 16f
                                style = Paint.Style.STROKE
                            }
                            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = android.graphics.Color.WHITE
                                textSize = 160f
                                typeface = android.graphics.Typeface.DEFAULT_BOLD
                                textAlign = Paint.Align.CENTER
                            }

                            val center = w / 2f
                            val size = 800f // 1600x1600 square to clearly show the orientation frame

                            canvas.drawRect(center - size, center - size, center + size, center + size, paint)
                            canvas.drawRect(center - size, center - size, center + size, center + size, strokePaint)

                            // Crosshairs for exact alignment verification
                            canvas.drawLine(center - size, center, center + size, center, strokePaint)
                            canvas.drawLine(center, center - size, center, center + size, strokePaint)

                            // Prove the Z-axis orientation didn't flip
                            canvas.drawText("TOP", center, center - size + 200f, textPaint)

                            rendererRef.value?.updateOverlayBitmap(orangeBmp)
                            arViewModel.updatePaintingGuide(orangeBmp)
                        } else {
                            val composite = withContext(Dispatchers.Default) {
                                compositeLayersForAr(visibleLayers)
                            }
                            rendererRef.value?.updateOverlayBitmap(composite)
                            arViewModel.updatePaintingGuide(composite)
                        }
                    }

                    AndroidView(
                        factory = { ctx ->
                            val renderer = ArRenderer(
                                context = ctx,
                                slamManager = slamManager,
                                onTargetCaptured = { bmp, cw, ch, depth, dw, dh, stride, intr, viewMat, _ ->
                                    bmp?.let { rawBmp ->
                                        val correctedRotation = if (rawBmp.width > rawBmp.height) 90 else 0
                                        arViewModel.onTargetCaptured(
                                            rawBmp, depth,
                                            cw, ch,
                                            dw, dh, stride,
                                            intr, viewMat, correctedRotation
                                        )
                                    }
                                },
                                onTrackingUpdated = { isTracking, splatCount, isDepthSupported, yaw, distanceMeters ->
                                    arViewModel.setTrackingState(isTracking, splatCount, isDepthSupported, yaw, distanceMeters)
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
                                colorFilter = ColorFilter.colorMatrix(
                                    createColorMatrix(
                                        saturation = layer.saturation,
                                        contrast = layer.contrast,
                                        brightness = layer.brightness,
                                        colorBalanceR = layer.colorBalanceR,
                                        colorBalanceG = layer.colorBalanceG,
                                        colorBalanceB = layer.colorBalanceB
                                    )
                                ),
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
                    layerBitmapKey = activeLayer.bitmap,
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

        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(56.dp),
                    color = Color(0xFFFFAA00),
                    strokeWidth = 4.dp
                )
            }
        }
    }
}

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
        val cm = createColorMatrix(
            saturation = layer.saturation,
            contrast = layer.contrast,
            brightness = layer.brightness,
            colorBalanceR = layer.colorBalanceR,
            colorBalanceG = layer.colorBalanceG,
            colorBalanceB = layer.colorBalanceB
        )
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(
            android.graphics.ColorMatrix(cm.values)
        )
        val matrix = Matrix()
        matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
        matrix.postScale(layer.scale, layer.scale)
        matrix.postRotate(layer.rotationZ)
        matrix.postTranslate(w / 2f + layer.offset.x, h / 2f + layer.offset.y)
        canvas.drawBitmap(bmp, matrix, paint)
    }
    return result
}