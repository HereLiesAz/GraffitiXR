package com.hereliesaz.graffitixr

import android.app.Activity
import android.opengl.GLSurfaceView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun ArView(
    viewModel: MainViewModel,
    uiState: UiState
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? Activity

    // Initialize Renderer
    val renderer = remember {
        ArRenderer(
            context,
            onPlanesDetected = { detected -> viewModel.setArPlanesDetected(detected) },
            onFrameCaptured = { bitmap -> viewModel.onFrameCaptured(bitmap) },
            onAnchorCreated = { viewModel.onArImagePlaced() }
        )
    }

    // Connect Renderer to ViewModel so ViewModel can trigger "Capture Target"
    DisposableEffect(renderer) {
        viewModel.arRenderer = renderer
        onDispose { viewModel.arRenderer = null }
    }

    // Sync UI State to Renderer
    renderer.opacity = uiState.opacity
    renderer.scale = uiState.arObjectScale
    renderer.rotationX = uiState.rotationX
    renderer.rotationY = uiState.rotationY
    renderer.rotationZ = uiState.rotationZ
    renderer.colorBalanceR = uiState.colorBalanceR
    renderer.colorBalanceG = uiState.colorBalanceG
    renderer.colorBalanceB = uiState.colorBalanceB

    // Sync AR State
    if (uiState.arState != renderer.arState) {
        renderer.arState = uiState.arState
    }

    // Load image
    if (uiState.overlayImageUri != null) {
        renderer.updateOverlayImage(uiState.overlayImageUri)
    }

    // Create GL View
    val glSurfaceView = remember {
        GLSurfaceView(context).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

    // Lifecycle Management
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
                    glSurfaceView.onPause()
                    renderer.onPause()
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
            // 1. Tap Detector (For placement and axis toggling)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        viewModel.onCycleRotationAxis()
                    },
                    onTap = { offset ->
                        if (uiState.arState == ArState.SEARCHING) {
                            renderer.queueTap(offset.x, offset.y)
                        }
                    }
                )
            }
            // 2. Transform Detector (Scale and Rotate)
            // KEY FIX: We pass 'uiState.activeRotationAxis' as the key.
            // This forces the block to restart when the axis changes,
            // ensuring the closure captures the NEW axis.
            .pointerInput(uiState.activeRotationAxis) {
                detectTransformGestures { _, _, zoom, rotation ->
                    // Scaling
                    viewModel.onArObjectScaleChanged(zoom)

                    // Rotation
                    // KEY FIX: Invert rotation sign (-rotation) to match finger direction
                    // OpenGL rotates CCW for positive values. Fingers usually expect CW.
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