package com.hereliesaz.graffitixr

import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun ARScreen(arCoreManager: ARCoreManager) {
    val renderer = remember {
        ARCoreRenderer(arCoreManager)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var glSurfaceView by remember { mutableStateOf<GLSurfaceView?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                glSurfaceView?.onResume()
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                glSurfaceView?.onPause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    AndroidView(
        factory = { context ->
            Log.d("ARScreen", "Creating GLSurfaceView") // Diagnostic log
            GLSurfaceView(context).apply {
                setZOrderOnTop(true)
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                holder.setFormat(PixelFormat.TRANSLUCENT)
                setEGLContextClientVersion(2)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                glSurfaceView = this
            }
        }
    )
}
