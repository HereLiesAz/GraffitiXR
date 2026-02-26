package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset

@Composable
fun MockupScreen(viewModel: EditorViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    // Example of a valid gesture detector call inside this screen
    /* Modifier.pointerInput(Unit) {
        detectTransformGestures { centroid, pan, zoom, rotation ->
            viewModel.onTransformGesture(centroid, pan, zoom, rotation)
        }
    }
    */

    // Logic for warp mesh updates
    uiState.activeLayerId?.let { layerId ->
        // When user manipulates mesh pins:
        // viewModel.onLayerWarpChanged(layerId, updatedPoints)
    }
}