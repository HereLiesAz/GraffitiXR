package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.nativebridge.SlamManager

@Composable
fun ArView(
    viewModel: ArViewModel,
    uiState: ArUiState,
    slamManager: SlamManager,
    projectRepository: ProjectRepository,
    activeLayer: Layer?,
    onRendererCreated: (ArRenderer) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // FIX: Correct constructor. SlamManager is injected/passed, Context is not needed for Renderer anymore.
    val renderer = remember(slamManager) { ArRenderer(slamManager) }

    // Notify parent about renderer creation
    LaunchedEffect(renderer) {
        onRendererCreated(renderer)
    }

    // Effect: Update Flashlight/Light Estimate
    LaunchedEffect(uiState.isFlashlightOn) {
        // Mapped 'setFlashlight' to 'updateLight'
        val intensity = if (uiState.isFlashlightOn) 1.0f else 0.5f // Simple simulation
        renderer.updateLightEstimate(intensity)
    }

    // Effect: Update Point Cloud Visibility
    LaunchedEffect(uiState.showPointCloud) {
        // Mapped 'showPointCloud' to 'setVisualizationMode' on SlamManager
        // 0 = Off/Normal, 1 = Point Cloud / Debug
        slamManager.setVisualizationMode(if (uiState.showPointCloud) 1 else 0)
    }

    // Effect: Update Overlay Layer
    LaunchedEffect(activeLayer) {
        if (activeLayer != null) {
            renderer.setOverlay(activeLayer.bitmap)
        }
    }

    AndroidView(
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                preserveEGLContextOnPause = true
                setEGLContextClientVersion(3)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

                // Handle Tap Gestures via TouchListener if needed,
                // or rely on Compose 'pointerInput' wrapper around AndroidView
            }
        },
        update = { glView ->
            // Lifecycle handling is done via DefaultLifecycleObserver in ArRenderer
            // We just ensure the renderer is attached.
        },
        modifier = Modifier.fillMaxSize()
    )

    // Lifecycle Observer
    DisposableEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(renderer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(renderer)
            // Cleanup is handled by SlamManager's destroy() in MainActivity
            // But we can pause the renderer here if needed.
        }
    }
}