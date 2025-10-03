package com.hereliesaz.graffitixr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.google.ar.core.Anchor
import com.hereliesaz.graffitixr.graphics.ArFeaturePattern

/**
 * A composable screen that hosts the Augmented Reality experience.
 *
 * This screen contains the `ArView` for rendering the scene and passes the
 * necessary state and event handlers to it.
 *
 * @param uiState The current UI state of the application.
 * @param onArImagePlaced A callback invoked when the user places the initial image.
 * @param onArFeaturesDetected A callback invoked when the feature "fingerprint" of the scene is generated.
 * @param modifier The modifier to be applied to the layout.
 */
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