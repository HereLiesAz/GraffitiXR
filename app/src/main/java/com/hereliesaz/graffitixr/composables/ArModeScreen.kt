package com.hereliesaz.graffitixr.composables

import android.Manifest
import android.opengl.GLSurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.hereliesaz.graffitixr.MainViewModel
import com.hereliesaz.graffitixr.UiState
import com.hereliesaz.graffitixr.graphics.ArRenderer

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ArModeScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val cameraPermissionState = rememberPermissionState(permission = Manifest.permission.CAMERA)

    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    if (cameraPermissionState.status.isGranted) {
        ArContent(viewModel = viewModel, uiState = uiState)
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Camera permission is required for AR mode.")
        }
    }
}

@Composable
private fun ArContent(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel,
    uiState: UiState
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val (renderer, glSurfaceView) = remember {
        val view = GLSurfaceView(context)
        val renderer = ArRenderer(
            context = context,
            view = view,
            onArImagePlaced = viewModel::onArImagePlaced,
            onArFeaturesDetected = viewModel::onArFeaturesDetected
        )
        view.apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setOnTouchListener { _, event ->
                renderer.onSurfaceTapped(event)
                true
            }
        }
        renderer to view
    }

    // Update the renderer's state whenever the UiState changes
    LaunchedEffect(uiState) {
        renderer.arImagePose = uiState.arImagePose?.let { pose ->
            FloatArray(16).also { pose.toMatrix(it, 0) }
        }
        renderer.arFeaturePattern = uiState.arFeaturePattern
        renderer.overlayImageUri = uiState.overlayImageUri
        renderer.isArLocked = uiState.isArLocked
        renderer.opacity = uiState.opacity
    }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    glSurfaceView.onResume()
                    renderer.onResume()
                }

                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
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
        factory = { glSurfaceView },
        modifier = modifier.fillMaxSize()
    )
}