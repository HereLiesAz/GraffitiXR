package com.hereliesaz.graffitixr.feature.ar

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ar.core.Session

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

@Composable
fun ArView(
    viewModel: ArViewModel,
    modifier: Modifier = Modifier,
    onSessionCreated: (Session) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // State to control rendering options
    var showPointCloud by remember { mutableStateOf(true) }
    var flashLightOn by remember { mutableStateOf(false) }

    // Create the custom View instance
    val arView = remember { GraffitiArView(context) }

    // Manage Lifecycle
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> arView.onResume()
                Lifecycle.Event.ON_PAUSE -> arView.onPause()
                Lifecycle.Event.ON_DESTROY -> arView.cleanup()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            arView.cleanup()
        }
    }

    // React to new target images being captured
    LaunchedEffect(Unit) {
        viewModel.newTargetImage.collect { (bitmap, name) ->
            arView.arRenderer.setupAugmentedImageDatabase(bitmap, name)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                addView(arView)
            }
        },
        update = { _ ->
            arView.setShowPointCloud(showPointCloud)
            arView.setFlashlight(flashLightOn)
        }
    )
}
