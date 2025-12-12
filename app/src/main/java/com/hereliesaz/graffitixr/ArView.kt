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

@Composable
fun ArView(
    viewModel: MainViewModel,
    uiState: UiState
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? Activity

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

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (activity != null) {
                        glSurfaceView.queueEvent { renderer.onResume(activity) }
                        glSurfaceView.onResume()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    glSurfaceView.onPause()
                    glSurfaceView.queueEvent { renderer.onPause() }
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
            // 2. Transform Logic
            // KEY FIX: Restarts detection when axis changes
            .pointerInput(uiState.activeRotationAxis) {
                detectTransformGestures { _, _, zoom, rotation ->
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
