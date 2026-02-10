package com.hereliesaz.graffitixr.feature.ar

import android.opengl.GLSurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer

@Composable
fun ArView(
    viewModel: ArViewModel,
    uiState: ArUiState,
    onRendererCreated: (ArRenderer) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Initialize Renderer
    val arRenderer = remember {
        ArRenderer(context).also {
            onRendererCreated(it)
        }
    }

    // Lifecycle Management
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> arRenderer.onResume(lifecycleOwner)
                Lifecycle.Event.ON_PAUSE -> arRenderer.onPause(lifecycleOwner)
                Lifecycle.Event.ON_DESTROY -> arRenderer.cleanup()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            arRenderer.cleanup()
        }
    }

    // React to UI State changes
    LaunchedEffect(uiState.showPointCloud) {
        arRenderer.showPointCloud = uiState.showPointCloud
    }

    LaunchedEffect(uiState.isFlashlightOn) {
        arRenderer.setFlashlight(uiState.isFlashlightOn)
    }

    // React to new target images
    LaunchedEffect(Unit) {
        viewModel.newTargetImage.collect { (bitmap, name) ->
            arRenderer.setupAugmentedImageDatabase(bitmap, name)
        }
    }

    // View Factory
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                preserveEGLContextOnPause = true
                setEGLContextClientVersion(3)
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                setRenderer(arRenderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        }
    )
}