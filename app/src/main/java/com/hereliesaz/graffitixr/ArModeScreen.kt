package com.hereliesaz.graffitixr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.google.ar.core.Anchor

/**
 * A composable screen that hosts the Augmented Reality experience.
 *
 * This screen contains the `ArView` for rendering the scene and passes the
 * necessary state and event handlers to it.
 *
 * @param uiState The current UI state of the application.
 * @param onArImagePlaced A callback invoked when the user places the initial image.
 * @param modifier The modifier to be applied to the layout.
 */
@Composable
fun ArModeScreen(
    uiState: UiState,
    onArImagePlaced: (Anchor) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // This ArView is for the PLACEMENT phase only.
        // The full renderer will be implemented in the next step.
        ArView(
            arImagePose = uiState.arImagePose,
            overlayImageUri = uiState.overlayImageUri,
            opacity = uiState.opacity,
            onArImagePlaced = onArImagePlaced
        )
    }
}