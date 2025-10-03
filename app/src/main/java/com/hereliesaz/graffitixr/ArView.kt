package com.hereliesaz.graffitixr

import android.net.Uri
import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ar.core.Anchor
import com.hereliesaz.graffitixr.graphics.ArFeaturePattern
import com.hereliesaz.graffitixr.graphics.ArRenderer

/**
 * A composable that provides a view for rendering the complete AR scene.
 *
 * @param arImagePose The pose of the manually placed image, as a model matrix.
 * @param arFeaturePattern The unique "fingerprint" of the locked AR scene.
 * @param overlayImageUri The URI of the image to be projected.
 * @param isArLocked A flag indicating whether the AR projection is locked.
 * @param opacity The opacity of the overlay image.
 * @param onArImagePlaced A callback invoked when the user places the initial image.
 * @param onArFeaturesDetected A callback invoked when the feature "fingerprint" of the scene is generated.
 */
@Composable
fun ArView(
    arImagePose: FloatArray?,
    arFeaturePattern: ArFeaturePattern?,
    overlayImageUri: Uri?,
    isArLocked: Boolean,
    opacity: Float,
    onArImagePlaced: (Anchor) -> Unit,
    onArFeaturesDetected: (ArFeaturePattern) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val glSurfaceView = remember { GLSurfaceView(context) }
    val renderer = remember {
        ArRenderer(context, glSurfaceView, onArImagePlaced, onArFeaturesDetected)
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
            renderer.arImagePose = arImagePose
            renderer.arFeaturePattern = arFeaturePattern
            renderer.overlayImageUri = overlayImageUri
            renderer.isArLocked = isArLocked
            renderer.opacity = opacity
        }
    )
}