package com.hereliesaz.graffitixr.feature.ar

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                addView(arRenderer.view)
            }
        }
    )
}
