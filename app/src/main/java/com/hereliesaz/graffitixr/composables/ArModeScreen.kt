package com.hereliesaz.graffitixr.composables

import android.Manifest
import android.opengl.GLSurfaceView
import android.os.Build
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
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.ar.core.Session
import com.hereliesaz.graffitixr.MainViewModel
import com.hereliesaz.graffitixr.UiState
import com.hereliesaz.graffitixr.graphics.ArRenderer

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ArModeScreen(viewModel: MainViewModel, arSession: Session?) {
    val uiState by viewModel.uiState.collectAsState()

    val permissions = mutableListOf(Manifest.permission.CAMERA)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
    } else {
        permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
    }
    val permissionStates = rememberMultiplePermissionsState(permissions = permissions)

    LaunchedEffect(Unit) {
        if (!permissionStates.allPermissionsGranted) {
            permissionStates.launchMultiplePermissionRequest()
        }
    }

    if (permissionStates.allPermissionsGranted) {
        ArContent(
            viewModel = viewModel,
            uiState = uiState,
            arSession = arSession
        )
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val revokedPermissions = permissionStates.revokedPermissions.joinToString { it.permission }
            Text("Permissions required for AR mode: $revokedPermissions. Please grant them in settings.")
        }
    }
}

@Composable
private fun ArContent(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel,
    uiState: UiState,
    arSession: Session?
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val (renderer, glSurfaceView) = remember(arSession) {
        val view = GLSurfaceView(context)
        val renderer = ArRenderer(
            context = context,
            view = view,
            session = arSession,
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
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> glSurfaceView.onResume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> glSurfaceView.onPause()
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