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
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.google.ar.core.Anchor
import android.view.GestureDetector
import com.google.ar.core.Pose
import com.hereliesaz.graffitixr.graphics.ArFeaturePattern
import com.hereliesaz.graffitixr.graphics.ArRenderer
import com.hereliesaz.graffitixr.utils.RotationGestureDetector

@Composable
fun ArView(
    arImagePose: Pose?,
    arFeaturePattern: ArFeaturePattern?,
    overlayImageUri: Uri?,
    arState: ArState,
    arObjectScale: Float,
    arObjectRotation: Float,
    opacity: Float,
    onArImagePlaced: (Anchor) -> Unit,
    onArFeaturesDetected: (ArFeaturePattern) -> Unit,
    onPlanesDetected: (Boolean) -> Unit,
    onArObjectScaleChanged: (Float) -> Unit,
    onArObjectRotationChanged: (Float) -> Unit,
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
            onPlanesDetected = onPlanesDetected
        )
    }

    val scaleDetector = remember {
        ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                onArObjectScaleChanged(detector.scaleFactor)
                return true
            }
        })
    }

    val rotationDetector = remember {
        RotationGestureDetector(object : RotationGestureDetector.OnRotationGestureListener {
            override fun onRotation(rotationDelta: Float) {
                onArObjectRotationChanged(rotationDelta)
            }
        })
    }

    val gestureDetector = remember {
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                renderer.onSurfaceTapped(e)
                return true
            }
        })
    }

    AndroidView(
        factory = {
            glSurfaceView.apply {
                setEGLContextClientVersion(3)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

                setOnTouchListener { _, event ->
                    var handled = scaleDetector.onTouchEvent(event)
                    handled = rotationDetector.onTouchEvent(event) || handled
                    handled = gestureDetector.onTouchEvent(event) || handled
                    handled
                }
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
            renderer.arState = arState
            renderer.arObjectScale = arObjectScale
            renderer.arObjectRotation = arObjectRotation
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