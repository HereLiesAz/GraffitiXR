package com.hereliesaz.graffitixr

import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.opengl.GLSurfaceView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult

@Composable
fun ARScreen(
    arCoreManager: ARCoreManager,
    uiState: UiState,
    onPlanesDetected: (Boolean) -> Unit,
    onImagePlaced: () -> Unit,
    onScaleChanged: (Float) -> Unit,
    onRotationChanged: (Float) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val renderer = remember {
        ARCoreRenderer(arCoreManager)
    }

    // Update renderer state
    SideEffect {
        renderer.arState = uiState.arState
        renderer.opacity = uiState.opacity
        renderer.colorBalanceR = uiState.colorBalanceR
        renderer.colorBalanceG = uiState.colorBalanceG
        renderer.colorBalanceB = uiState.colorBalanceB
        renderer.arObjectScale = uiState.arObjectScale
        renderer.arObjectRotation = uiState.rotationZ

        renderer.onPlanesDetected = onPlanesDetected
        renderer.onImagePlaced = onImagePlaced
    }

    // Load Overlay Image
    LaunchedEffect(uiState.overlayImageUri) {
        val uri = uiState.overlayImageUri
        if (uri != null) {
            val loader = ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(uri)
                .allowHardware(false)
                .build()
            val result = loader.execute(request)
            if (result is SuccessResult) {
                val drawable = result.drawable
                if (drawable is BitmapDrawable) {
                    renderer.overlayBitmap = drawable.bitmap
                }
            }
        } else {
            renderer.overlayBitmap = null
        }
    }

    // Create and remember the GLSurfaceView instance
    val glSurfaceView = remember {
        GLSurfaceView(context).apply {
            tag = "GLSurfaceView"
            setZOrderMediaOverlay(true)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

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

    AndroidView(
        factory = { glSurfaceView },
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    renderer.onTap(offset.x, offset.y)
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, rotation ->
                    onScaleChanged(zoom)
                    onRotationChanged(rotation)
                    renderer.onTransform(centroid.x, centroid.y, pan.x, pan.y)
                }
            }
    )
}
