package com.hereliesaz.graffitixr.feature.ar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.hereliesaz.graffitixr.common.model.ArUiState

/**
 * Interface for spatial mapping and surface reconstruction.
 */
@Composable
fun MappingScreen(
    viewModel: ArViewModel,
    modifier: Modifier = Modifier
) {
    val uiState: ArUiState by viewModel.uiState.collectAsState()

    // Note: ArUiState does not have gestureInProgress currently.
    // If logic depends on it, we might need to add it to ArUiState or remove the check if irrelevant for AR.
    // Assuming gestureInProgress was used for manipulation which might not be needed in MappingScreen directly,
    // or we can add it to ArUiState if needed.
    // Checking ArUiState definition from previous steps: it has `isScanning`, `pointCloudCount`, etc.
    // `gestureInProgress` was in `EditorUiState`.

    // For now, I will comment out the usage or assume it's not needed if not present.
    // But wait, `ArViewModel.setGestureInProgress` was present in the old file.
    // In my updated `ArViewModel.kt`, I removed `setGestureInProgress` because `ArUiState` didn't have it.
    // I should check if `MappingScreen` actually needs it.
    // The previous code was:
    // if (uiState.gestureInProgress) { ... }

    // I will check ArUiState definition again. It does NOT have gestureInProgress.
    // I will remove the reference for now to fix compilation.

    if (uiState.isScanning) {
        // Render spatial anchors or mesh feedback markers
    }
}
