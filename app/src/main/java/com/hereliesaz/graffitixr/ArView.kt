package com.hereliesaz.graffitixr

import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.ar.core.Session
import com.hereliesaz.graffitixr.graphics.ArRenderer

/**
 * A composable that provides a view for rendering the complete AR scene.
 *
 * @param uiState The current UI state.
 * @param onSessionInitialized A callback invoked when the AR session is initialized.
 */
@Composable
fun ArView(
    uiState: UiState,
    onSessionInitialized: (Session) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val glSurfaceView = remember { GLSurfaceView(context) }
    val renderer = remember {
        ArRenderer(context, onSessionInitialized)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    glSurfaceView.onResume()
                    renderer.onResume()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    renderer.onPause()
                    glSurfaceView.onPause()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    AndroidView(
        factory = {
            glSurfaceView.apply {
                setEGLContextClientVersion(2)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                setOnTouchListener { _, event ->
                    renderer.onSurfaceTapped(event)
                    true
                }
            }
        },
        update = {
            renderer.uiState = uiState
            uiState.overlayImageUri?.let { uri -> renderer.updateTexture(uri) }
        }

    )
}