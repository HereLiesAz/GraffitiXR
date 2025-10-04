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
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.hereliesaz.graffitixr.graphics.ArFeaturePattern
import com.hereliesaz.graffitixr.graphics.ArRenderer
import com.hereliesaz.graffitixr.graphics.Quaternion
import com.hereliesaz.graffitixr.utils.MultiGestureDetector

@Composable
fun ArView(
    arImagePose: Pose?,
    arFeaturePattern: ArFeaturePattern?,
    overlayImageUri: Uri?,
    arObjectOrientation: Quaternion,
    arObjectScale: Float,
    isArLocked: Boolean,
    opacity: Float,
    onArImagePlaced: (Anchor) -> Unit,
    onArFeaturesDetected: (ArFeaturePattern) -> Unit,
    onArObjectScaleChanged: (Float) -> Unit,
    onArObjectRotated: (pitch: Float, yaw: Float, roll: Float) -> Unit,
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

    val multiGestureDetector = remember {
        MultiGestureDetector(object : MultiGestureDetector.OnMultiGestureListener {
            override fun onScale(scaleFactor: Float) {
                onArObjectScaleChanged(scaleFactor)
            }

            override fun onRotate(rotationDelta: Float) {
                onArObjectRotated(0f, 0f, rotationDelta)
            }

            override fun onPan(deltaX: Float, deltaY: Float) {
                // Adjust sensitivity for pitch and yaw
                val yaw = -deltaX * 0.005f
                val pitch = -deltaY * 0.005f
                onArObjectRotated(pitch, yaw, 0f)
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
                    var handled = false
                    if (arImagePose != null) { // Only handle gestures if an object is placed
                        handled = multiGestureDetector.onTouchEvent(event)
                    }
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
            renderer.arObjectOrientation = arObjectOrientation
            renderer.arObjectScale = arObjectScale
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