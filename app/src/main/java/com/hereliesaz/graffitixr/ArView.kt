package com.hereliesaz.graffitixr

import android.opengl.GLSurfaceView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ar.core.Anchor
import com.hereliesaz.graffitixr.graphics.ArRenderer

/**
 * A simplified composable that hosts the ARCore [GLSurfaceView].
 *
 * Its primary responsibilities are:
 * - Displaying the camera feed and detected planes via the [ArRenderer].
 * - Detecting tap gestures and forwarding them to the renderer for hit-testing and anchor placement.
 * - Passing a reference to the created [ArRenderer] instance up to the calling composable,
 *   so that the UI layer can access the view and projection matrices for coordinate projection.
 */
@Composable
fun ArView(
    onArImagePlaced: (Anchor) -> Unit,
    onPlanesDetected: (Boolean) -> Unit,
    getRenderer: (ArRenderer) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val glSurfaceView = remember { GLSurfaceView(context) }
    val renderer = remember {
        ArRenderer(
            context = context,
            view = glSurfaceView,
            onArImagePlaced = onArImagePlaced,
            onPlanesDetected = onPlanesDetected,
        ).also(getRenderer)
    }

    AndroidView(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = { offset -> renderer.onSurfaceTapped(offset.x, offset.y) }
            )
        },
        factory = {
            glSurfaceView.apply {
                setEGLContextClientVersion(3)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        }
    )

    DisposableEffect(lifecycleOwner, renderer, glSurfaceView) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                renderer.resume()
                glSurfaceView.onResume()
            }

            override fun onPause(owner: LifecycleOwner) {
                glSurfaceView.onPause()
                renderer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}