package com.hereliesaz.graffitixr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.google.ar.core.Anchor
import com.hereliesaz.graffitixr.graphics.ArFeaturePattern

@Composable
fun ArModeScreen(
    uiState: UiState,
    onArImagePlaced: (Anchor) -> Unit,
    onArFeaturesDetected: (ArFeaturePattern) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        ArView(
            arImagePose = uiState.arImagePose,
            arFeaturePattern = uiState.arFeaturePattern,
            overlayImageUri = uiState.overlayImageUri,
            isArLocked = uiState.isArLocked,
            opacity = uiState.opacity,
            onArImagePlaced = onArImagePlaced,
            onArFeaturesDetected = onArFeaturesDetected
        )
    }
}