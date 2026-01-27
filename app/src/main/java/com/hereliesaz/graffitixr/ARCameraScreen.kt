package com.hereliesaz.graffitixr

import android.graphics.RectF
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hereliesaz.graffitixr.rendering.AugmentedImageRenderer

@Composable
fun ARCameraScreen(
    viewModel: MainViewModel,
    onFrameCaptured: (android.graphics.Bitmap) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()

    // We use a clean side-effect to manage the renderer lifecycle
    val arRenderer = remember {
        ArRenderer(
            context = context,
            onPlanesDetected = { viewModel.setArPlanesDetected(it) },
            onFrameCaptured = { 
                viewModel.onFrameCaptured(it) 
                onFrameCaptured(it)
            },
            onProgressUpdated = { prog, bmp -> viewModel.onProgressUpdate(prog, bmp) },
            onTrackingFailure = { viewModel.onTrackingFailure(it) },
            onBoundsUpdated = { viewModel.updateArtworkBounds(it) },
            // Pass a mutable state for the renderer to consume when it's ready to create an anchor
            anchorCreationPose = mutableStateOf(null) 
        )
    }

    // Pass layer updates to renderer
    LaunchedEffect(uiState.layers) {
        arRenderer.updateLayers(uiState.layers)
    }

    // Handle Capture Events
    LaunchedEffect(Unit) {
        viewModel.captureEvent.collect { event ->
            when(event) {
                is com.hereliesaz.graffitixr.data.CaptureEvent.RequestCapture -> {
                    arRenderer.triggerCapture()
                }
                is com.hereliesaz.graffitixr.data.CaptureEvent.RequestCalibration -> {
                    val pose = arRenderer.getLatestPose()
                    if (pose != null) {
                        val matrix = FloatArray(16)
                        pose.toMatrix(matrix, 0)
                        viewModel.onCalibrationPointCaptured(matrix)
                    }
                }
                else -> {}
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.onResume()
                    // We need the activity context for ARCore install requests
                    (context as? android.app.Activity)?.let { arRenderer.onResume(it) }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.onPause()
                    arRenderer.onPause()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            arRenderer.cleanup()
        }
    }

    AndroidView(
        factory = { ctx ->
            val surfaceView = GLSurfaceView(ctx).apply {
                preserveEGLContextOnPause = true
                setEGLContextClientVersion(3) // Important for GLES 3.0
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                setRenderer(arRenderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }

            // Touch forwarding for placement
            surfaceView.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    arRenderer.queueTap(event.x, event.y)
                    viewModel.onGestureEnd()
                }
                true // Consume event
            }

            surfaceView
        },
        modifier = Modifier.fillMaxSize()
    )
}
