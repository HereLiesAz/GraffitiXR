package com.hereliesaz.graffitixr.composables

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.hereliesaz.graffitixr.ArView
import com.hereliesaz.graffitixr.MainViewModel
import com.hereliesaz.graffitixr.UiState

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ArModeScreen(viewModel: MainViewModel) {
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
        ArContent(uiState = uiState, viewModel = viewModel)
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val revokedPermissions = permissionStates.revokedPermissions.joinToString { it.permission }
            Text("Permissions required for AR mode: $revokedPermissions. Please grant them in settings.")
        }
    }
}

@Composable
private fun ArContent(
    uiState: UiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    ArView(
        modifier = modifier.fillMaxSize(),
        arImagePose = uiState.arImagePose,
        arFeaturePattern = uiState.arFeaturePattern,
        overlayImageUri = uiState.overlayImageUri,
        arObjectOrientation = uiState.arObjectOrientation,
        arObjectScale = uiState.arObjectScale,
        isArLocked = uiState.isArLocked,
        opacity = uiState.opacity,
        onArImagePlaced = viewModel::onArImagePlaced,
        onArFeaturesDetected = viewModel::onArFeaturesDetected,
        onArObjectScaleChanged = viewModel::onArObjectScaleChanged,
        onArObjectRotated = viewModel::onArObjectRotated
    )
}