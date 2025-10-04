package com.hereliesaz.graffitixr.composables

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.hereliesaz.graffitixr.ArView
import com.hereliesaz.graffitixr.MainViewModel

@Composable
fun ArModeScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    ArView(
        arImagePose = uiState.arImagePose,
        arFeaturePattern = uiState.arFeaturePattern,
        overlayImageUri = uiState.overlayImageUri,
        isArLocked = uiState.isArLocked,
        opacity = uiState.opacity,
        onArImagePlaced = viewModel::onArImagePlaced,
        onArFeaturesDetected = viewModel::onArFeaturesDetected,
        modifier = Modifier.fillMaxSize()
    )
}
