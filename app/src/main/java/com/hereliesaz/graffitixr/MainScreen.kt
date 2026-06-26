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
import com.hereliesaz.graffitixr.common.model.ModeAdjustment
import com.hereliesaz.graffitixr.common.model.FeedbackEvent
import com.hereliesaz.graffitixr.common.model.Tool
import android.widget.Toast
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

        // Surface one-off AR failures (camera unavailable, session-init failure) as a toast
        // so the user isn't left on a silent frozen preview.
        LaunchedEffect(Unit) {
            arViewModel.feedback.collect { event ->
                val message = when (event) {
                    is FeedbackEvent.Error -> event.message
                    is FeedbackEvent.Toast -> event.message
                    else -> null
                }
                if (message != null) Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }

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
                        // Release the CameraX (editor/overlay) camera BEFORE ARCore opens the
                        // device. Otherwise the two camera clients race for the device: ARCore
                        // opening evicts CameraX (ERROR_CAMERA_DEVICE) and CameraX's in-flight
                        // capture-session open then throws an uncaught camera SecurityException
                        // ("Attempt to use camera from a different process than original client"),
                        // hard-crashing the app on AR entry.
                        runCatching { cameraController.unbind() }
                        arViewModel.setArMode(true, context)
                        onDispose {
                            // Fully close the session (not just pause) when leaving AR — e.g. into
                            // the library to load a project, then back via Trace. A paused-but-open
                            // session gets resumed on re-entry instead of rebuilt, which on many
                            // devices comes back as a black/uninitialised camera. exitArMode() tears
                            // it down so the next AR entry creates a fresh session.
                            arViewModel.exitArMode()
                        }
                    }

                    // Enter AR with the Target button pre-selected when no target exists yet, so the
                    // first screen tap (once tracking allows) creates the target without a detour to
                    // the rail. If a target was already created, stay in normal layer-editing mode.
                    LaunchedEffect(mainUiState.hasExistingTarget) {
                        // Only act once the project state is resolved (non-null). `== false` means a
                        // loaded project with no saved target, so pre-select the Target button.
                        if (mainUiState.hasExistingTarget == false && !mainUiState.isCapturingTarget) {
                            mainViewModel.startTargetCapture()
                        }
                    }

                    // Method-aware layer defaults: on AR entry and whenever the mural method
                    // changes, the matching representation defaults on and the other two off.
                    // Keyed on the method, so a user who manually enables another layer keeps it
                    // until the method changes again.
                    LaunchedEffect(arUiState.muralMethod) {
                        editorViewModel.applyMethodLayerDefaults(arUiState.muralMethod)
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

                    // Dead-camera watchdog. On some devices (e.g. Samsung SM-A236U) the camera HAL
                    // never feeds ARCore — the session opens but no frame ever lands (frame.timestamp
                    // stays 0). Left alone, ARCore's internal camera pipe keeps thrashing the device
                    // through ERROR_CAMERA_DEVICE/reopen cycles, which can end in an uncatchable
                    // teardown crash. If no frame has arrived after the timeout, leave AR: switching
                    // editorMode off AR disposes this branch, whose onDispose calls exitArMode() for a
                    // clean teardown. Keyed on the renderer so it (re)starts with each AR session and
                    // is cancelled the moment we leave AR.
                    LaunchedEffect(rendererRef.value) {
                        val r = rendererRef.value ?: return@LaunchedEffect
                        val startMs = android.os.SystemClock.elapsedRealtime()
                        while (true) {
                            kotlinx.coroutines.delay(1000)
                            // Read via a :feature:ar helper that returns a Long — :app can't access
                            // ARCore's Frame type (it's an implementation dep of :feature:ar).
                            val ts = com.hereliesaz.graffitixr.feature.ar.lastArFrameTimestampNs(r)
                            if (ts > 0L) break // camera is streaming — healthy, stop watching
                            val elapsed = android.os.SystemClock.elapsedRealtime() - startMs
                            if (com.hereliesaz.graffitixr.feature.ar.ArCameraHealth.isCameraDead(elapsed, ts)) {
                                Toast.makeText(
                                    context,
                                    "Camera isn't delivering frames — leaving AR. Reopen AR to try again.",
                                    Toast.LENGTH_LONG
                                ).show()
                                editorViewModel.setEditorMode(EditorMode.DESIGN)
                                break
                            }
                        }
                    }

                    val visibleLayers = uiState.layers.filter { it.isVisible && it.bitmap != null }

                    // Push the AR whole-design adjustment (set via the rail's "Layer" item) to the
                    // renderer, which applies it as an in-plane move/scale/rotate of the overlay along
                    // the wall plane. Offsets are stored in world meters (converted at the gesture site).
                    val arOverlayAdj = uiState.modeAdjustments[EditorMode.AR]
                    LaunchedEffect(arOverlayAdj, rendererRef.value) {
                        rendererRef.value?.let { r ->
                            r.overlayPanX = arOverlayAdj?.offsetX ?: 0f
                            r.overlayPanY = arOverlayAdj?.offsetY ?: 0f
                            r.overlayScale = arOverlayAdj?.scale ?: 1f
                            r.overlayRotationDeg = arOverlayAdj?.rotation ?: 0f
                        }
                    }

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
                                onTargetCaptured = { bmp, cw, ch, depth, dw, dh, stride, intr, viewMat, rot, tapDist, wallPlane ->
                                    arViewModel.onTargetCaptured(
                                        bmp, depth,
                                        cw, ch,
                                        dw, dh, stride,
                                        intr, viewMat, rot, tapDist, wallPlane
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
                                },
                                onAnchorEstablished = {
                                    arViewModel.onPrimaryAnchorEstablished()
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
                                r.visitedSectorsMask = arUiState.visitedSectorsMask
                                r.scanPhase = arUiState.scanPhase
                                // Independent in-world perception layers (Settings, default on).
                                r.showFeaturePoints = uiState.showFeaturePoints
                                r.showPlaneGrids = uiState.showPlaneGrids
                                r.showVoxels = uiState.showVoxels
                                r.showPoints = uiState.showPoints
                                r.showMesh = uiState.showMesh
                                // Perception throttle: system triggers (OR-ed in the VM) + the lag
                                // trigger's enable flag (evaluated renderer-side).
                                r.systemThrottle = arUiState.perceptionSystemThrottle
                                r.lagThrottleEnabled = arUiState.throttleOnLag
                                // Adaptive idle rate: policy ceilings from the VM + the live
                                // interaction flag (so a gesture instantly forces full rate).
                                r.adaptiveRateEnabled = arUiState.adaptiveRateEnabled
                                r.idleRateCeilingFps = arUiState.idleRateCeilingFps
                                r.activeRateCeilingFps = arUiState.activeRateCeilingFps
                                r.gestureInProgress = uiState.gestureInProgress
                            }
                        },
                        onRelease = { view ->
                            // The AR view is leaving composition (mode switch / teardown).
                            // Free GL objects on the GL thread while the context is still
                            // alive, then run non-GL teardown (cancels the renderer's
                            // coroutine scope and detaches the session).
                            rendererRef.value?.let { r ->
                                r.isDestroying = true
                                view.queueEvent { r.releaseGlResources() }
                                r.destroy()
                            }
                            rendererRef.value = null
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
                // DESIGN needs no camera preview here; the canvas (layers, gestures, drawing) is
                // drawn full-screen by the shared background rendering below, so it fills the
                // screen behind the rail as a background component.
                EditorMode.DESIGN -> {}
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
            // Per-mode whole-design adjustment: position/scale/rotate/fade and tone the entire
            // composited design as a unit for this mode (DESIGN mode is the global, unadjusted view).
            val modeAdj = if (uiState.editorMode != EditorMode.DESIGN) {
                uiState.modeAdjustments[uiState.editorMode] ?: ModeAdjustment()
            } else {
                ModeAdjustment()
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = modeAdj.offsetX
                        translationY = modeAdj.offsetY
                        scaleX = modeAdj.scale
                        scaleY = modeAdj.scale
                        rotationZ = modeAdj.rotation
                        alpha = modeAdj.opacity
                        transformOrigin = TransformOrigin.Center
                    }
            ) {
                uiState.layers.filter { it.isVisible }.forEach { layer ->
                    androidx.compose.runtime.key(layer.id) {
                        val isLive = layer.id == uiState.liveStrokeLayerId
                        val bmp = if (isLive) uiState.liveStrokeBitmap ?: layer.bitmap else layer.bitmap
                        bmp?.let { displayBmp ->
                            val imageBitmap = if (isLive) {
                                val version = uiState.liveStrokeVersion
                                remember(version) { displayBmp.asImageBitmap() }
                            } else {
                                remember(displayBmp) { displayBmp.asImageBitmap() }
                            }
                            // Memoize the colour filter. createColorMatrix() was rebuilt on EVERY
                            // recomposition for EVERY layer — a per-frame allocation storm that makes
                            // the whole screen lag. Recompute only when the inputs actually change.
                            val colorFilter = remember(
                                layer.saturation, layer.contrast, layer.brightness,
                                layer.colorBalanceR, layer.colorBalanceG, layer.colorBalanceB,
                                layer.isInverted, modeAdj.saturation, modeAdj.contrast,
                                modeAdj.brightness, modeAdj.isInverted
                            ) {
                                ColorFilter.colorMatrix(
                                    createColorMatrix(
                                        saturation = layer.saturation * modeAdj.saturation,
                                        contrast = layer.contrast * modeAdj.contrast,
                                        brightness = layer.brightness + modeAdj.brightness,
                                        colorBalanceR = layer.colorBalanceR,
                                        colorBalanceG = layer.colorBalanceG,
                                        colorBalanceB = layer.colorBalanceB,
                                        isInverted = layer.isInverted != modeAdj.isInverted
                                    )
                                )
                            }
                            // Offscreen compositing forces a full-screen offscreen buffer + extra pass
                            // per layer, every frame. It's only needed to isolate a non-default blend
                            // mode; for normal (SrcOver) layers, Auto avoids that cost.
                            val needsOffscreen =
                                layer.blendMode != androidx.compose.ui.graphics.BlendMode.SrcOver
                            Image(
                                bitmap = imageBitmap,
                                contentDescription = null,
                                colorFilter = colorFilter,
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
                                        compositingStrategy = if (needsOffscreen)
                                            androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                                        else
                                            androidx.compose.ui.graphics.CompositingStrategy.Auto
                                    },
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                }
            }
        }

        val isGuest = arUiState.coopRole == com.hereliesaz.graffitixr.common.model.CoopRole.GUEST

        if (uiState.editorMode != EditorMode.STENCIL && isCameraActive) {
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
                                    // A plain tap no longer creates an AR target. Target creation only
                                    // happens while the Target button is selected (isWaitingForTap):
                                    // once a target is accepted the user must re-select that button to
                                    // make another, and the active layer takes over screen gestures.
                                    onTap = { editorViewModel.onDismissPanel() }
                                )
                            }
                        }
                    }
                    .pointerInput(uiState.activeLayerId, isImageLocked, uiState.activeTool, isWaitingForTap, isTouchLocked, uiState.editingModeLayer) {
                        // While editing the whole design as a unit for this mode, transform gestures
                        // drive the mode adjustment instead of the active layer.
                        val editingMode = uiState.editingModeLayer && uiState.editorMode != EditorMode.DESIGN
                        // editingMode edits the whole design, so it isn't gated by the active layer's
                        // image lock (the mode has its own Lock via isTransformLocked, in the reducer).
                        if (!isTouchLocked && !isWaitingForTap && (editingMode || (!isImageLocked && activeLayer != null))) {
                            if (uiState.activeTool == Tool.NONE) {
                                detectSmartOverlayGestures(
                                    getValidBounds = { androidx.compose.ui.geometry.Rect(0f, 0f, size.width.toFloat(), size.height.toFloat()) },
                                    onGestureStart = { editorViewModel.onGestureStart() },
                                    onGestureEnd = { editorViewModel.onGestureEnd() },
                                    onGesture = { _, pan, zoom, rotation ->
                                        if (editingMode) {
                                            // In AR the overlay lives on the wall in meters, so convert
                                            // the screen-pixel drag to in-plane meters (screen Y is down,
                                            // plane local Y is up → flip Y). Other modes stay in pixels.
                                            val adjustedPan = if (uiState.editorMode == EditorMode.AR) {
                                                val mpp = rendererRef.value?.currentMetersPerPixel ?: 0f
                                                androidx.compose.ui.geometry.Offset(pan.x * mpp, -pan.y * mpp)
                                            } else pan
                                            editorViewModel.onModeTransformGesture(uiState.editorMode, adjustedPan, zoom, rotation)
                                        } else {
                                            editorViewModel.onTransformGesture(pan, zoom, rotation)
                                        }
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
    // -Float.MAX_VALUE, not Float.MIN_VALUE (the smallest *positive* float): a layer panned into
    // negative screen coordinates would otherwise never update max, undersizing the composite.
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE

    for (layer in layers) {
        val bmp = layer.bitmap ?: continue
        val halfW = bmp.width / 2f * layer.scale
        val halfH = bmp.height / 2f * layer.scale
        // The layer is drawn rotated about its center, so the composite must be sized to the ROTATED
        // bounding box. Using the unrotated half-extents here undersized the canvas and clipped the
        // corners of any rotated layer.
        val rad = Math.toRadians(layer.rotationZ.toDouble())
        val cos = kotlin.math.abs(kotlin.math.cos(rad)).toFloat()
        val sin = kotlin.math.abs(kotlin.math.sin(rad)).toFloat()
        val rotHalfW = halfW * cos + halfH * sin
        val rotHalfH = halfW * sin + halfH * cos
        val left = layer.offset.x - rotHalfW
        val top = layer.offset.y - rotHalfH
        val right = layer.offset.x + rotHalfW
        val bottom = layer.offset.y + rotHalfH

        if (left < minX) minX = left
        if (top < minY) minY = top
        if (right > maxX) maxX = right
        if (bottom > maxY) maxY = bottom
    }

    val rawWidth = (maxX - minX).coerceAtLeast(1f)
    val rawHeight = (maxY - minY).coerceAtLeast(1f)

    // Cap the composite size: a zoomed-in (large layer.scale) or far-panned layer can otherwise
    // request a multi-hundred-MB ARGB_8888 bitmap and OOM. Downscale proportionally past the cap so
    // the result still fits as an overlay texture; downscale == 1 leaves the common case untouched.
    val maxDim = 4096f
    val downscale = minOf(1f, maxDim / rawWidth, maxDim / rawHeight)
    val width = (rawWidth * downscale).toInt().coerceAtLeast(1)
    val height = (rawHeight * downscale).toInt().coerceAtLeast(1)

    val result = createBitmap(width, height, AndroidBitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    // Layer matrices are built in raw composite coordinates; this maps them into the capped bitmap.
    canvas.scale(downscale, downscale)
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
