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

    if (uiState.gestureInProgress) {
        // Render spatial anchors or mesh feedback markers
    }
}