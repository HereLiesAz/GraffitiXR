package com.hereliesaz.graffitixr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun ArView(
    viewModel: MainViewModel,
    uiState: UiState
) {
    val context = LocalContext.current

    // Instantiate Renderer with callbacks to VM
    val arRenderer = remember {
        ArRenderer(
            context = context,
            onPlanesDetected = viewModel::setArPlanesDetected,
            onFrameCaptured = viewModel::onFrameCaptured,
            onAnchorCreated = viewModel::onArImagePlaced,
            onProgressUpdated = viewModel::onProgressUpdate,
            onTrackingFailure = viewModel::onTrackingFailure,
            onBoundsUpdated = viewModel::updateArtworkBounds
        ).also {
            viewModel.arRenderer = it
        }
    }

    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity
        activity?.let { arRenderer.onResume(it) }
        onDispose {
            arRenderer.onPause()
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
            }
        }
    )
}