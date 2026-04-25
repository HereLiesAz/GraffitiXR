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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.Transparent
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
import androidx.compose.ui.res.stringResource
import com.hereliesaz.graffitixr.design.R as DesignR
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
import com.hereliesaz.graffitixr.feature.ar.FreezePreviewScreen
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.feature.editor.DrawingCanvas
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.design.detectSmartOverlayGestures
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
    mainViewModel: MainViewModel,
    editorViewModel: EditorViewModel,
    arViewModel: ArViewModel,
    slamManager: SlamManager,
    hasCameraPermission: Boolean,
    cameraController: androidx.camera.view.LifecycleCameraController,
    onRendererCreated: (ArRenderer) -> Unit,
    isExporting: Boolean = false
) {
    val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }
    val isImageLocked = activeLayer?.isImageLocked ?: false
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val rendererRef = remember { mutableStateOf<ArRenderer?>(null) }

    val bgColor = if (uiState.editorMode == EditorMode.AR || uiState.editorMode == EditorMode.OVERLAY) Transparent else uiState.canvasBackground
    Box(modifier = Modifier.fillMaxSize().background(bgColor)) {

        DisposableEffect(lifecycleOwner) {
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                arViewModel.onActivityResumed()
            }

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

        if (hasCameraPermission && isCameraActive && uiState.editorMode != EditorMode.TRACE && uiState.editorMode != EditorMode.STENCIL) {
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

                        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            glView?.onResume()
                        }

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
                                onTargetCaptured = { bmp, cw, ch, depth, dw, dh, stride, intr, viewMat, rot ->
                                    arViewModel.onTargetCaptured(
                                        bmp, depth,
                                        cw, ch,
                                        dw, dh, stride,
                                        intr, viewMat, rot
                                    )
                                },
                                onTrackingUpdated = { isTracking, splatCount, immutableSplatCount, isDepthSupported, yaw, distanceMeters, relDir, isDualLens, isHardwareStereo, centerDepth, visConf, globConf ->
                                    arViewModel.setTrackingState(
                                        isTracking, splatCount, immutableSplatCount, isDepthSupported, yaw, distanceMeters, relDir,
                                        isDualLens, isHardwareStereo, centerDepth, visConf, globConf
                                    )
                                },
                                onLightUpdated = { level ->
                                    arViewModel.updateLightLevel(level)
                                    slamManager.updateLightLevel(level)
                                },
                                onDiag = { text ->
                                    arViewModel.appendDiag(text)
                                }
                            )
                            renderer.hideVisualization = isExporting
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
                            rendererRef.value?.let { r ->
                                r.scanMode = arUiState.arScanMode
                                r.muralMethod = arUiState.muralMethod
                                r.captureRequested = arUiState.isCaptureRequested
                                r.isCapturingTarget = mainUiState.isCapturingTarget
                                r.isInPlaneRealignment = mainUiState.isInPlaneRealignment
                                r.hideVisualization = isExporting
                            }
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
                else -> {}
            }
        }

        // Freeze preview — shown when user freezes layers in AR mode
        arUiState.freezePreviewBitmap?.let { annotated ->
            FreezePreviewScreen(
                annotatedBitmap = annotated,
                showDepthWarning = arUiState.freezeDepthWarning,
                onDismiss = { arViewModel.onFreezeDismissed() },
                onUnfreeze = { arViewModel.onUnfreezeRequested() }
            )
        }

        uiState.backgroundBitmap?.takeIf { uiState.editorMode == EditorMode.MOCKUP }
            ?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = stringResource(DesignR.string.desc_bg_mockup),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }


        if (uiState.editorMode != EditorMode.AR && uiState.editorMode != EditorMode.STENCIL) {
            uiState.layers.filter { it.isVisible }.forEach { layer ->
                val isLive = layer.id == uiState.liveStrokeLayerId
                val bmp = if (isLive) uiState.liveStrokeBitmap ?: layer.bitmap else layer.bitmap
                bmp?.let { displayBmp ->
                    val imageBitmap = if (isLive) {
                        val version = uiState.liveStrokeVersion
                        remember(version) { displayBmp.asImageBitmap() }
                    } else {
                        remember(displayBmp) { displayBmp.asImageBitmap() }
                    }
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = null,
                        colorFilter = ColorFilter.colorMatrix(
                            createColorMatrix(
                                saturation = layer.saturation,
                                contrast = layer.contrast,
                                brightness = layer.brightness,
                                colorBalanceR = layer.colorBalanceR,
                                colorBalanceG = layer.colorBalanceG,
                                colorBalanceB = layer.colorBalanceB,
                                isInverted = layer.isInverted
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

        val isGuest = arUiState.coopRole == com.hereliesaz.graffitixr.common.model.CoopRole.GUEST

        if (uiState.editorMode != EditorMode.STENCIL) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(uiState.activeLayerId, isImageLocked, uiState.activeTool, isWaitingForTap, isTouchLocked, isGuest) {
                        if (isGuest) return@pointerInput // Block ALL guest interaction with layers

                        if (isWaitingForTap) {
                            detectTapGestures { offset ->
                                val nx = offset.x / size.width
                                val ny = offset.y / size.height
                                arViewModel.onScreenTap(nx, ny)
                            }
                        } else if (!isTouchLocked && !isImageLocked) {
                            if (uiState.activeTool == Tool.NONE) {
                                detectTapGestures(
                                    onDoubleTap = { editorViewModel.onCycleRotationAxis() },
                                    onTap = { offset ->
                                        editorViewModel.onDismissPanel()
                                        if (uiState.editorMode == EditorMode.AR && !mainUiState.isCapturingTarget) {
                                            mainViewModel.startTargetCapture()
                                            val nx = offset.x / size.width
                                            val ny = offset.y / size.height
                                            arViewModel.onScreenTap(nx, ny)
                                        }
                                    }
                                )
                            }
                        }
                    }
                    .pointerInput(uiState.activeLayerId, isImageLocked, uiState.activeTool, isWaitingForTap, isTouchLocked) {
                        if (!isTouchLocked && !isImageLocked && activeLayer != null && !isWaitingForTap) {
                            if (uiState.activeTool == Tool.NONE) {
                                detectSmartOverlayGestures(
                                    getValidBounds = { androidx.compose.ui.geometry.Rect(0f, 0f, size.width.toFloat(), size.height.toFloat()) },
                                    onGestureStart = { editorViewModel.onGestureStart() },
                                    onGestureEnd = { editorViewModel.onGestureEnd() },
                                    onGesture = { _, pan, zoom, rotation ->
                                        editorViewModel.onTransformGesture(pan, zoom, rotation)
                                    }
                                )
                            }
                        }
                    }
            ) {}
        }

        if (!isTouchLocked && !isImageLocked && activeLayer != null && !isGuest) {
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
                    onStrokeStart = { offset, size -> editorViewModel.onStrokeStart(offset, size) },
                    onStrokePoint = { offset -> editorViewModel.onStrokePoint(offset) },
                    onStrokeEnd = { editorViewModel.onStrokeEnd() }
                )
            }
        }

        if (uiState.isLoading && !isExporting) {
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

        val segmentationPreview = uiState.segmentationPreview
        if (uiState.isSegmenting && segmentationPreview != null && !isExporting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.8f))
            ) {
                Image(
                    bitmap = segmentationPreview.asImageBitmap(),
                    contentDescription = "Segmentation Preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

internal fun compositeLayersForAr(layers: List<Layer>): AndroidBitmap {
    if (layers.isEmpty()) return createBitmap(1, 1, AndroidBitmap.Config.ARGB_8888)

    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = Float.MIN_VALUE
    var maxY = Float.MIN_VALUE

    for (layer in layers) {
        val bmp = layer.bitmap ?: continue
        val halfW = bmp.width / 2f * layer.scale
        val halfH = bmp.height / 2f * layer.scale
        val left = layer.offset.x - halfW
        val top = layer.offset.y - halfH
        val right = layer.offset.x + halfW
        val bottom = layer.offset.y + halfH

        if (left < minX) minX = left
        if (top < minY) minY = top
        if (right > maxX) maxX = right
        if (bottom > maxY) maxY = bottom
    }

    val width = (maxX - minX).coerceAtLeast(1f).toInt()
    val height = (maxY - minY).coerceAtLeast(1f).toInt()

    val result = createBitmap(width, height, AndroidBitmap.Config.ARGB_8888)
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
            colorBalanceB = layer.colorBalanceB,
            isInverted = layer.isInverted
        )
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(
            android.graphics.ColorMatrix(cm.values)
        )
        val matrix = Matrix()
        matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
        matrix.postScale(layer.scale, layer.scale)
        matrix.postRotate(layer.rotationZ)
        matrix.postTranslate(layer.offset.x - minX, layer.offset.y - minY)
        canvas.drawBitmap(bmp, matrix, paint)
    }
    return result
}