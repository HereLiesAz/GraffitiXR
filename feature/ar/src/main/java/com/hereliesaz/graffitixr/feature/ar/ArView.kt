package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.opengl.GLSurfaceView
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

    val arRenderer = remember {
        ArRenderer(context).also {
            onRendererCreated(it)
        }
    }

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

    LaunchedEffect(uiState.showPointCloud) {
        arRenderer.showPointCloud = uiState.showPointCloud
    }

    LaunchedEffect(uiState.isFlashlightOn) {
        arRenderer.setFlashlight(uiState.isFlashlightOn)
    }

    LaunchedEffect(Unit) {
        viewModel.newTargetImage.collect { (bitmap, name) ->
            arRenderer.setupAugmentedImageDatabase(bitmap, name)
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx: Context ->
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