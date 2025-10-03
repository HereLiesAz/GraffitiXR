package com.hereliesaz.graffitixr

import android.net.Uri
import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.hereliesaz.graffitixr.graphics.ArFeaturePattern
import com.hereliesaz.graffitixr.graphics.ArRenderer

@Composable
fun ArView(
    arImagePose: Pose?,
    arFeaturePattern: ArFeaturePattern?,
    overlayImageUri: Uri?,
    isArLocked: Boolean,
    opacity: Float,
    onArImagePlaced: (Anchor) -> Unit,
    onArFeaturesDetected: (ArFeaturePattern) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val glSurfaceView = remember { GLSurfaceView(context) }
    val renderer = remember {
        ArRenderer(
            context = context,
            view = glSurfaceView,
            onArImagePlaced = onArImagePlaced,
            onArFeaturesDetected = onArFeaturesDetected
        )
    }

    AndroidView(
        factory = {
            glSurfaceView.apply {
                setEGLContextClientVersion(3)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                setRenderer(renderer)
            }
        },
        modifier = modifier,
        update = {
            renderer.overlayImageUri = overlayImageUri
            renderer.opacity = opacity
            renderer.arImagePose = arImagePose?.let { pose ->
                FloatArray(16).also { pose.toMatrix(it, 0) }
            }
            renderer.arFeaturePattern = arFeaturePattern
            renderer.isArLocked = isArLocked
        }
    )

    DisposableEffect(lifecycleOwner, renderer) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                renderer.onResume()
            }

            override fun onPause(owner: LifecycleOwner) {
                renderer.onPause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}
