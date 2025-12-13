package com.hereliesaz.graffitixr

import android.app.Activity
import android.opengl.GLSurfaceView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hereliesaz.graffitixr.data.FingerprintSerializer
import kotlinx.serialization.json.Json

/**
 * A Composable that hosts the ARCore view using a [GLSurfaceView].
 *
 * It bridges the gap between Jetpack Compose and the legacy Android View system (OpenGL).
 * It also handles:
 * 1.  Lifecycle management for the AR session.
 * 2.  Initialization of the [ArRenderer].
 * 3.  Connecting Compose gestures (Tap, Pan, Zoom, Rotate) to the Renderer.
 * 4.  Synchronizing state (e.g., opacity, scale) from [UiState] to the Renderer.
 *
 * @param viewModel The MainViewModel to dispatch events to.
 * @param uiState The current UI state to render.
 */
@Composable
fun ArView(
    viewModel: MainViewModel,
    uiState: UiState
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? Activity

    // Initialize the custom OpenGL Renderer
    val renderer = remember {
        ArRenderer(
            context,
            onPlanesDetected = { detected -> viewModel.setArPlanesDetected(detected) },
            onFrameCaptured = { bitmap -> viewModel.onFrameCaptured(bitmap) },
            onAnchorCreated = { viewModel.onArImagePlaced() },
            onProgressUpdated = { progress, bitmap -> viewModel.onProgressUpdate(progress, bitmap) },
            onTrackingFailure = { message -> viewModel.onTrackingFailure(message) }
        )
    }

    // Initialize the GLSurfaceView
    val glSurfaceView = remember {
        GLSurfaceView(context).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

    // Inject renderer into ViewModel for direct commands (like capture)
    DisposableEffect(renderer) {
        viewModel.arRenderer = renderer
        onDispose {
            viewModel.arRenderer = null
            renderer.cleanup()
        }
    }

    // Deserialize and set fingerprint if available
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

    // Load Augmented Images for tracking
    LaunchedEffect(uiState.capturedTargetImages) {
        if(uiState.capturedTargetImages.isNotEmpty()) {
            glSurfaceView.queueEvent { renderer.setAugmentedImageDatabase(uiState.capturedTargetImages) }
        }
    }

    // Sync UI State to Renderer properties
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

    // Handle Lifecycle (Resume/Pause AR Session)
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
                        if (uiState.arState == ArState.SEARCHING) {
                            glSurfaceView.queueEvent { renderer.queueTap(offset.x, offset.y) }
                        }
                    }
                )
            }
            // 2. Transform Logic (Single & Multi-touch)
            // KEY FIX: Restarts detection when axis changes
            .pointerInput(uiState.activeRotationAxis) {
                detectTransformGestures { _, pan, zoom, rotation ->
                    // Pan (Single or Multi-touch)
                    glSurfaceView.queueEvent { renderer.queuePan(pan.x, pan.y) }

                    viewModel.onArObjectScaleChanged(zoom)

                    // KEY FIX: Invert rotation for natural feel
                    val rotationDelta = -rotation

                    when (uiState.activeRotationAxis) {
                        RotationAxis.X -> viewModel.onRotationXChanged(rotationDelta)
                        RotationAxis.Y -> viewModel.onRotationYChanged(rotationDelta)
                        RotationAxis.Z -> viewModel.onRotationZChanged(rotationDelta)
                    }
                }
            }
    )
}
