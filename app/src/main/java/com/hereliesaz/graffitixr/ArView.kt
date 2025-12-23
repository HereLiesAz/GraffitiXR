package com.hereliesaz.graffitixr

import android.app.Activity
import android.opengl.GLSurfaceView
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hereliesaz.graffitixr.data.FingerprintSerializer
import kotlinx.serialization.json.Json
import kotlin.math.abs

@Composable
fun ArView(
    viewModel: MainViewModel,
    uiState: UiState
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? Activity
    val artworkBounds by viewModel.artworkBounds.collectAsState()

    val renderer = remember {
        ArRenderer(
            context,
            onPlanesDetected = { detected -> viewModel.setArPlanesDetected(detected) },
            onFrameCaptured = { bitmap -> viewModel.onFrameCaptured(bitmap) },
            onAnchorCreated = { viewModel.onArImagePlaced() },
            onProgressUpdated = { progress, bitmap -> viewModel.onProgressUpdate(progress, bitmap) },
            onTrackingFailure = { message -> viewModel.onTrackingFailure(message) },
            onBoundsUpdated = { bounds -> viewModel.updateArtworkBounds(bounds) }
        )
    }

    val glSurfaceView = remember {
        GLSurfaceView(context).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

    DisposableEffect(renderer) {
        viewModel.arRenderer = renderer
        onDispose {
            viewModel.arRenderer = null
            renderer.cleanup()
        }
    }

    val fingerprintJson = uiState.fingerprintJson
    val fingerprint = remember(fingerprintJson) {
        if (fingerprintJson != null) {
            try {
                Json.decodeFromString(FingerprintSerializer, fingerprintJson)
            } catch (e: Exception) {
                null
            }
        } else null
    }

    LaunchedEffect(fingerprint) {
        fingerprint?.let {
            glSurfaceView.queueEvent { renderer.setFingerprint(it) }
        }
    }

    LaunchedEffect(uiState.capturedTargetImages) {
        if(uiState.capturedTargetImages.isNotEmpty()) {
            glSurfaceView.queueEvent { renderer.setAugmentedImageDatabase(uiState.capturedTargetImages) }
        }
    }

    renderer.opacity = uiState.opacity
    renderer.scale = uiState.arObjectScale
    renderer.rotationX = uiState.rotationX
    renderer.rotationY = uiState.rotationY
    renderer.rotationZ = uiState.rotationZ
    renderer.colorBalanceR = uiState.colorBalanceR
    renderer.colorBalanceG = uiState.colorBalanceG
    renderer.colorBalanceB = uiState.colorBalanceB

    if (uiState.arState != renderer.arState) {
        renderer.arState = uiState.arState
    }

    if (uiState.overlayImageUri != null) {
        renderer.updateOverlayImage(uiState.overlayImageUri)
    }

    renderer.isAnchorReplacementAllowed = uiState.isCapturingTarget

    val guideBitmap = remember(uiState.targetCreationMode, uiState.gridRows, uiState.gridCols) {
        if (uiState.targetCreationMode == TargetCreationMode.GUIDED_GRID) {
            com.hereliesaz.graffitixr.utils.GuideGenerator.generateGrid(uiState.gridRows, uiState.gridCols)
        } else if (uiState.targetCreationMode == TargetCreationMode.GUIDED_POINTS) {
            com.hereliesaz.graffitixr.utils.GuideGenerator.generateFourXs()
        } else {
            null
        }
    }
    renderer.guideBitmap = guideBitmap
    renderer.showGuide = uiState.isGridGuideVisible

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (activity != null) {
                        renderer.onResume(activity)
                        glSurfaceView.onResume()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    renderer.onPause()
                    glSurfaceView.onPause()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    AndroidView(
        factory = { glSurfaceView },
        modifier = Modifier
            .fillMaxSize()
            // 1. Tap Logic
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        viewModel.onCycleRotationAxis()
                    },
                    onTap = { offset ->
                        if (uiState.arState == ArState.SEARCHING || uiState.isCapturingTarget) {
                            glSurfaceView.queueEvent { renderer.queueTap(offset.x, offset.y) }
                        }
                    }
                )
            }
            // 2. Advanced Transform Logic with Hit Testing
            .pointerInput(uiState.activeRotationAxis, uiState.isGridGuideVisible) {
                awaitEachGesture {
                    var rotation = 0f
                    var zoom = 1f
                    var pan = androidx.compose.ui.geometry.Offset.Zero
                    var pastTouchSlop = false
                    val touchSlop = viewConfiguration.touchSlop

                    // Wait for initial down
                    val down = awaitFirstDown(requireUnconsumed = false)

                    // Determine if drag is allowed at start
                    // 1 finger: must be inside bounds
                    // 2+ fingers: always allowed
                    // Note: 'down' only gives us the first pointer.
                    // If a second pointer comes down, the gesture continues.

                    val bounds = artworkBounds
                    val startPoint = down.position
                    val isStartOnObject = bounds?.contains(startPoint.x, startPoint.y) == true

                    // We don't block here yet because user might add a second finger.

                    do {
                        val event = awaitPointerEvent()
                        val canceled = event.changes.any { it.isConsumed }
                        if (canceled) break

                        val pointerCount = event.changes.size

                        // Rule Check:
                        // If 1 pointer -> Allowed (Global drag to support robust moving even if bounds are flaky)
                        // If 2+ pointers -> Allowed
                        // Note: If capturing target, we only allow ZOOM.
                        val isGestureAllowed = pointerCount >= 1

                        if (isGestureAllowed) {
                            val zoomChange = event.calculateZoom()
                            val rotationChange = event.calculateRotation()
                            val panChange = event.calculatePan()

                            if (!pastTouchSlop) {
                                zoom *= zoomChange
                                rotation += rotationChange
                                pan += panChange

                                val centroidSize = event.calculateCentroid(useCurrent = false)
                                val zoomMotion = abs(1 - zoom) * centroidSize.getDistance()
                                val rotationMotion = abs(rotation * kotlin.math.PI.toFloat() * centroidSize.getDistance() / 180f)
                                val panMotion = pan.getDistance()

                                if (zoomMotion > touchSlop ||
                                    rotationMotion > touchSlop ||
                                    panMotion > touchSlop
                                ) {
                                    pastTouchSlop = true
                                }
                            }

                            if (pastTouchSlop) {
                                // Pan: Block only if showing grid guide
                                if (panChange != androidx.compose.ui.geometry.Offset.Zero && !uiState.isGridGuideVisible) {
                                    glSurfaceView.queueEvent { renderer.queuePan(panChange.x, panChange.y) }
                                }

                                // Zoom: Allow always (as requested)
                                if (zoomChange != 1f) {
                                    viewModel.onArObjectScaleChanged(zoomChange)
                                }

                                // Rotation: Block only if showing grid guide, unless creating Grid Target (Force Y)
                                if (rotationChange != 0f) {
                                    val rotationDelta = -rotationChange
                                    if (uiState.targetCreationMode == TargetCreationMode.GUIDED_GRID && uiState.isGridGuideVisible) {
                                        viewModel.onRotationYChanged(rotationDelta)
                                    } else if (!uiState.isGridGuideVisible) {
                                        when (uiState.activeRotationAxis) {
                                            RotationAxis.X -> viewModel.onRotationXChanged(rotationDelta)
                                            RotationAxis.Y -> viewModel.onRotationYChanged(rotationDelta)
                                            RotationAxis.Z -> viewModel.onRotationZChanged(rotationDelta)
                                        }
                                    }
                                }
                                event.changes.forEach {
                                    if (it.positionChanged()) {
                                        it.consume()
                                    }
                                }
                            }
                        }
                    } while (!canceled && event.changes.any { it.pressed })
                }
            }
    )
}