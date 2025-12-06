package com.hereliesaz.graffitixr

import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun ARScreen(arCoreManager: ARCoreManager) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val renderer = remember {
        ARCoreRenderer(arCoreManager)
    }

    // Create and remember the GLSurfaceView instance
    val glSurfaceView = remember {
        GLSurfaceView(context).apply {
            tag = "GLSurfaceView"
            setZOrderOnTop(true)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            setEGLContextClientVersion(2)
            setRenderer(renderer) // setRenderer must be called before setRenderMode
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

    // Use DisposableEffect to manage the lifecycle of the GLSurfaceView
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> glSurfaceView.onResume()
                Lifecycle.Event.ON_PAUSE -> glSurfaceView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Use the remembered GLSurfaceView instance in the AndroidView
    AndroidView({ glSurfaceView })
}
