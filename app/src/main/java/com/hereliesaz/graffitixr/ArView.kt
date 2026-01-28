package com.hereliesaz.graffitixr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hereliesaz.graffitixr.data.CaptureEvent

@Composable
fun ArView(
    viewModel: MainViewModel,
    uiState: UiState,
    onRendererCreated: (ArRenderer) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Instantiate Renderer once and keep it
    val arRenderer = remember {
        ArRenderer(
            context = context,
            onPlanesDetected = viewModel::setArPlanesDetected,
            onFrameCaptured = viewModel::onFrameCaptured,
            onProgressUpdated = viewModel::onProgressUpdate,
            onTrackingFailure = viewModel::onTrackingFailure,
            onBoundsUpdated = viewModel::updateArtworkBounds
        ).also {
            it.onAnchorCreated = {
                if (!uiState.isArTargetCreated) {
                    viewModel.onCreateTargetClicked()
                }
                viewModel.onArImagePlaced()
            }
        }
    }
    
    // Capture GLSurfaceView to manage lifecycle
    var glSurfaceViewRef by remember { mutableStateOf<android.opengl.GLSurfaceView?>(null) }

    // Report renderer instance to parent
    LaunchedEffect(arRenderer) {
        onRendererCreated(arRenderer)
    }

    // Pass layer updates to renderer
    LaunchedEffect(uiState.layers) {
        arRenderer.updateLayers(uiState.layers)
    }

    // Pass flashlight state
    LaunchedEffect(uiState.isFlashlightOn) {
        arRenderer.setFlashlight(uiState.isFlashlightOn)
    }

    // Handle Capture Events from ViewModel
    LaunchedEffect(viewModel) {
        viewModel.captureEvent.collect { event ->
            when(event) {
                is CaptureEvent.RequestCapture -> {
                    arRenderer.triggerCapture()
                }
                is CaptureEvent.RequestCalibration -> {
                    val pose = arRenderer.getLatestPose()
                    if (pose != null) {
                        val matrix = FloatArray(16)
                        pose.toMatrix(matrix, 0)
                        viewModel.onCalibrationPointCaptured(matrix)
                    }
                }
                is CaptureEvent.RequestFingerprint -> {
                    val fingerprint = arRenderer.generateFingerprint(event.bitmap)
                    viewModel.onFingerprintGenerated(fingerprint)
                }
                else -> {}
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val activity = context as? android.app.Activity ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    arRenderer.onResume(activity)
                    glSurfaceViewRef?.onResume()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    arRenderer.onPause()
                    glSurfaceViewRef?.onPause()
                }
                Lifecycle.Event.ON_DESTROY -> {
                    arRenderer.cleanup()
                }
                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            arRenderer.onPause()
            glSurfaceViewRef?.onPause()
        }
    }

    AndroidView(
        factory = { ctx ->
            android.opengl.GLSurfaceView(ctx).apply {
                preserveEGLContextOnPause = true
                setEGLContextClientVersion(3)
                // Standard configuration for ARCore
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                setRenderer(arRenderer)
                renderMode = android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY
                glSurfaceViewRef = this
            }
        },
        update = {
            // Update reference if view is re-used/re-created
            glSurfaceViewRef = it
        },
        modifier = Modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = { offset ->
                    arRenderer.queueTap(offset.x, offset.y)
                }
            )
        }
    )
}
