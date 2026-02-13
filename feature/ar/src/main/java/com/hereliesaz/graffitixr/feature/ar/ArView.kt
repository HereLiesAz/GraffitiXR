package com.hereliesaz.graffitixr.feature.ar

import android.opengl.GLSurfaceView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.nativebridge.SlamManager

@Composable
fun ArView(
    viewModel: ArViewModel,
    uiState: ArUiState,
    slamManager: SlamManager, // FIXED: Added
    activeLayer: Layer? = null,
    onRendererCreated: (ArRenderer) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // FIXED: Passed slamManager to constructor
    val renderer = remember { ArRenderer(context, slamManager) }

    LaunchedEffect(renderer) { onRendererCreated(renderer) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> renderer.onResume(lifecycleOwner)
                Lifecycle.Event.ON_PAUSE -> renderer.onPause(lifecycleOwner)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            renderer.cleanup()
        }
    }

    LaunchedEffect(uiState.isFlashlightOn) { renderer.setFlashlight(uiState.isFlashlightOn) }
    LaunchedEffect(uiState.showPointCloud) { renderer.showPointCloud = uiState.showPointCloud }
    LaunchedEffect(activeLayer) { renderer.setLayer(activeLayer) }

    AndroidView(
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                preserveEGLContextOnPause = true
                setEGLContextClientVersion(3)
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                
                // Link the view to the renderer for lifecycle synchronization
                renderer.glSurfaceView = this
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { offset ->
                    renderer.handleTap(offset.x, offset.y)
                })
            }
    )
}