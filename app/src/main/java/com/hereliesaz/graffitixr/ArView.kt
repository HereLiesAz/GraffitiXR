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

@Composable
fun ArView(
    viewModel: MainViewModel,
    uiState: UiState
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Capture GLSurfaceView to manage lifecycle
    var glSurfaceView by remember { mutableStateOf<android.opengl.GLSurfaceView?>(null) }

    // Instantiate Renderer with callbacks to VM
    val arRenderer = remember {
        ArRenderer(
            context = context,
            onPlanesDetected = viewModel::setArPlanesDetected,
            onFrameCaptured = viewModel::onFrameCaptured,
            onAnchorCreated = {
                if (!uiState.isArTargetCreated) {
                    viewModel.onCreateTargetClicked()
                }
                viewModel.onArImagePlaced()
            },
            onProgressUpdated = viewModel::onProgressUpdate,
            onTrackingFailure = viewModel::onTrackingFailure,
            onBoundsUpdated = viewModel::updateArtworkBounds
        ).also {
            viewModel.arRenderer = it
        }
    }

    LaunchedEffect(uiState.layers) {
        arRenderer.updateLayers(uiState.layers)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val activity = context as? android.app.Activity ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    arRenderer.onResume(activity)
                    glSurfaceView?.onResume()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    arRenderer.onPause()
                    glSurfaceView?.onPause()
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
