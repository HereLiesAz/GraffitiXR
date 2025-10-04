package com.hereliesaz.graffitixr

import android.net.Uri
import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.hereliesaz.graffitixr.graphics.ArFeaturePattern
import com.hereliesaz.graffitixr.graphics.ArRenderer

@Composable
fun ArView(
    arSession: Session?,
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

    val glSurfaceView = remember { GLSurfaceView(context) }
    val renderer = remember {
        ArRenderer(
            context = context,
            view = glSurfaceView,
            session = arSession,
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
}