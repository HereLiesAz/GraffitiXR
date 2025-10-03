package com.hereliesaz.graffitixr.composables

import android.Manifest
import android.opengl.GLSurfaceView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.ar.core.Session
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
        ArContent(uiState = uiState)
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Camera permission is required for AR mode.")
        }
    }
}

@Composable
private fun ArContent(modifier: Modifier = Modifier, uiState: UiState) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var arSession by remember { mutableStateOf<Session?>(null) }

    val renderer = remember {
        ArRenderer(context) { session -> arSession = session }
    }

    // Update the renderer's state whenever the UiState changes
    renderer.uiState = uiState

    LaunchedEffect(uiState.overlayImageUri) {
        uiState.overlayImageUri?.let { renderer.updateTexture(it) }
    }

    val glSurfaceView = remember {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
        }
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
            arSession?.close()
        }
    }

    AndroidView(
        factory = { glSurfaceView },
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    arSession?.let { session ->
                        val frame = session.update()
                        renderer.handleTap(frame, offset.x, offset.y)
                    }
                }
            }
    )
}