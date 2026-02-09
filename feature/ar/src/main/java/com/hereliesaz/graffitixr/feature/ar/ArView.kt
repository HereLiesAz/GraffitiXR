package com.hereliesaz.graffitixr.feature.ar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ar.core.Session

@Composable
fun ArScreen(
    onArSessionCreated: (Session) -> Unit
) {
    ArView(onSessionCreated = onArSessionCreated)
}

@Composable
fun ArView(
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

    AndroidView(
        modifier = modifier,
        factory = {
            arView
        },
        update = { view ->
            // Explicitly named parameter 'view' avoids "Unresolved reference: it" issues
            view.setShowPointCloud(showPointCloud)
            view.setFlashlight(flashLightOn)

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
    )
}
