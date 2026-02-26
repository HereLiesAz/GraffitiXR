package com.hereliesaz.graffitixr.feature.editor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.RotationAxis

/**
 * Renders visual feedback when a transformation gesture is active.
 */
@Composable
fun GestureFeedback(state: EditorUiState) {
    // These properties are now confirmed to be in the EditorUiState data class
    val showFeedback = state.gestureInProgress || state.showRotationAxisFeedback
    val activeLayer = state.layers.find { it.id == state.activeLayerId }
    val isLocked = activeLayer?.isImageLocked ?: false

    if (showFeedback && !isLocked) {
        val axisLabel = when (state.activeRotationAxis) {
            RotationAxis.X -> "Rotating on X-Axis"
            RotationAxis.Y -> "Rotating on Y-Axis"
            RotationAxis.Z -> "Rotating on Z-Axis"
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = axisLabel,
                color = when (state.activeRotationAxis) {
                    RotationAxis.X -> Color.Red
                    RotationAxis.Y -> Color.Green
                    RotationAxis.Z -> Color.Blue
                }
            )
        }
    }
}