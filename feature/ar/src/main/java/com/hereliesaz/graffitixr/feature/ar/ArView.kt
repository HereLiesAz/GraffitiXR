package com.hereliesaz.graffitixr.feature.ar

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ar.core.Session
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer

@Composable
fun ArScreen(
    onArSessionCreated: (Session) -> Unit
) {
    val viewModel: ArViewModel = hiltViewModel()
    ArView(
        viewModel = viewModel,
        onSessionCreated = onArSessionCreated
    )
}
import com.hereliesaz.graffitixr.common.model.ArUiState

@Composable
fun ArView(
    viewModel: ArViewModel,
    uiState: ArUiState,
    onRendererCreated: (ArRenderer) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Memoize the renderer so it survives recompositions but dies with the View
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

    // React to UI State changes (e.g., Point Cloud toggle)
    // Note: We trigger these side-effects here to ensure the renderer stays in sync with State
    LaunchedEffect(uiState.showPointCloud) {
        arRenderer.setShowPointCloud(uiState.showPointCloud)
    }

    LaunchedEffect(uiState.isFlashlightOn) {
        // Assuming ArRenderer has a flashlight handler or delegates to Session
        arRenderer.setFlashlight(uiState.isFlashlightOn)
    }

    // React to new target images being captured
    LaunchedEffect(Unit) {
        viewModel.newTargetImage.collect { (bitmap, name) ->
            arRenderer.setupAugmentedImageDatabase(bitmap, name)
        }
    }

    val factory = remember(arRenderer) {
        { ctx: android.content.Context ->
            FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                addView(arRenderer.view)
            }
        }
    }

    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = factory
    )
}
