package com.hereliesaz.graffitixr.feature.ar

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

    // Capture GLSurfaceView to manage lifecycle
    var glSurfaceView by remember { mutableStateOf<android.opengl.GLSurfaceView?>(null) }

    // Instantiate Renderer
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
            // FIX: Removed viewModel.arRenderer assignment
        }
    }
    
    // FIX: Report renderer instance to parent
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

    // NEW: Handle Capture Events from ViewModel
    LaunchedEffect(viewModel) {
        viewModel.captureEvent.collect { event ->
            when(event) {
                is CaptureEvent.RequestCapture -> {
                    // Trigger renderer frame capture
                    arRenderer.triggerCapture()
                }
                is CaptureEvent.RequestCalibration -> {
                    // Get latest pose and send back to ViewModel
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
                    glSurfaceView?.onResume()
                    viewModel.onResume()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    arRenderer.onPause()
                    glSurfaceView?.onPause()
                    viewModel.onPause()
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
            glSurfaceView?.onPause()
            arRenderer.cleanup()
        }
    }

    AndroidView(
        factory = { ctx ->
            android.opengl.GLSurfaceView(ctx).apply {
                preserveEGLContextOnPause = true
                setEGLContextClientVersion(3)
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                setRenderer(arRenderer)
                renderMode = android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY
                glSurfaceView = this
            }
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
