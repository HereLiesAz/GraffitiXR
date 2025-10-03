package com.hereliesaz.graffitixr

import android.net.Uri
import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.google.ar.core.Anchor
import com.hereliesaz.graffitixr.graphics.ArFeaturePattern
import com.hereliesaz.graffitixr.graphics.ArRenderer

@Composable
fun ArView(
    arImagePose: FloatArray?,
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

    val renderer = remember {
        ArRenderer(
            context = context,
            onArImagePlaced = onArImagePlaced,
            onArFeaturesDetected = onArFeaturesDetected
        )
    }

    DisposableEffect(overlayImageUri, opacity, arImagePose, arFeaturePattern, isArLocked) {
        overlayImageUri?.let { renderer.setOverlayImage(it) }
        renderer.setOpacity(opacity)
        renderer.setArImagePose(arImagePose)
        renderer.setArFeaturePattern(arFeaturePattern)
        renderer.setArLocked(isArLocked)
        onDispose { }
    }

    AndroidView(
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                setEGLContextClientVersion(3)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        },
        modifier = modifier
    ) { view ->
        renderer.attachLifecycle(lifecycleOwner.lifecycle)
    }
}