package com.hereliesaz.graffitixr.composables

import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import android.util.Log
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hereliesaz.graffitixr.VuforiaManager

private const val TAG = "VuforiaCameraScreen"

@Composable
fun VuforiaCameraScreen() {
    val lifecycleOwner = LocalLifecycleOwner.current
    val glSurfaceView = VuforiaManager.getGLSurfaceView()

    // Handles GLSurfaceView pause/resume according to the Activity lifecycle
    DisposableEffect(lifecycleOwner, glSurfaceView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    Log.d(TAG, "GLSurfaceView onResume")
                    glSurfaceView?.onResume()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    Log.d(TAG, "GLSurfaceView onPause")
                    glSurfaceView?.onPause()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            Log.d(TAG, "GLSurfaceView DisposableEffect onDispose")
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Handles Vuforia Engine start/stop.
    // Starts when the composable becomes active and resumes.
    // Stops when the composable becomes inactive or pauses.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    Log.d(TAG, "VuforiaManager.start() called from ON_RESUME")
                    VuforiaManager.start()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    Log.d(TAG, "VuforiaManager.stop() called from ON_PAUSE")
                    VuforiaManager.stop()
                }
                else -> {}
            }
        }
        // If we are already resumed, start the engine.
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            Log.d(TAG, "VuforiaManager.start() called from RESUMED state")
            VuforiaManager.start()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            // Stop the engine when the composable is disposed.
            Log.d(TAG, "VuforiaManager.stop() called from onDispose")
            VuforiaManager.stop()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    glSurfaceView?.let { view ->
        AndroidView({ view })
    }
}
