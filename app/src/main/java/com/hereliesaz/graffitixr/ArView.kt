package com.hereliesaz.graffitixr

import android.net.Uri
import android.opengl.GLSurfaceView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.hereliesaz.graffitixr.graphics.ArFeaturePattern
import com.hereliesaz.graffitixr.graphics.ArRenderer
import com.hereliesaz.graffitixr.graphics.Quaternion

@Composable
fun ArView(
    arImagePose: Pose?,
    arFeaturePattern: ArFeaturePattern?,
    overlayImageUri: Uri?,
    arState: ArState,
    arObjectScale: Float,
    arObjectOrientation: Quaternion,
    opacity: Float,
    activeRotationAxis: RotationAxis,
    onArImagePlaced: (Anchor) -> Unit,
    onArFeaturesDetected: (ArFeaturePattern) -> Unit,
    onPlanesDetected: (Boolean) -> Unit,
    onArObjectScaleChanged: (Float) -> Unit,
    onArObjectRotated: (pitch: Float, yaw: Float, roll: Float) -> Unit,
    onArObjectPanned: (Offset) -> Unit,
    onCycleRotationAxis: () -> Unit,
    onArDrawingProgressChanged: (Float) -> Unit,
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
            onArFeaturesDetected = onArFeaturesDetected,
            onPlanesDetected = onPlanesDetected,
            onArDrawingProgressChanged = onArDrawingProgressChanged
        )
    }

    val transformState = rememberTransformableState { zoomChange, panChange, rotationChange ->
        onArObjectScaleChanged(zoomChange)
        onArObjectPanned(panChange)
        when (activeRotationAxis) {
            RotationAxis.X -> onArObjectRotated(rotationChange, 0f, 0f)
            RotationAxis.Y -> onArObjectRotated(0f, rotationChange, 0f)
            RotationAxis.Z -> onArObjectRotated(0f, 0f, rotationChange)
        }
    }

    Box(
        modifier = modifier
            .transformable(state = transformState)
            .pointerInput(activeRotationAxis) {
                detectTapGestures(
                    onTap = { offset -> renderer.onSurfaceTapped(offset.x, offset.y) },
                    onDoubleTap = { onCycleRotationAxis() }
                )
            }
    ) {
        AndroidView(
            factory = {
                glSurfaceView.apply {
                    setEGLContextClientVersion(3)
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                }
            },
            update = {
                renderer.overlayImageUri = overlayImageUri
                renderer.opacity = opacity
                renderer.arImagePose = arImagePose?.let { pose ->
                    FloatArray(16).also { pose.toMatrix(it, 0) }
                }
                renderer.arFeaturePattern = arFeaturePattern
                renderer.arState = arState
                renderer.arObjectScale = arObjectScale
                renderer.arObjectOrientation = arObjectOrientation
            }
        )
    }

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
