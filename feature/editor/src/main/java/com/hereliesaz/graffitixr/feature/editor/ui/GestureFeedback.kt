// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/ui/GestureFeedback.kt
package com.hereliesaz.graffitixr.feature.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.RotationAxis

/**
 * Renders visual feedback when a transformation gesture is active.
 */
@Composable
fun GestureFeedback(state: EditorUiState, modifier: Modifier = Modifier) {
    // showRotationAxisFeedback is deliberately NOT included here: MainScreen.kt already mounts
    // the dedicated RotationAxisFeedback composable for that exact transient signal (localized,
    // properly animated in/out, and self-clearing via onFeedbackShown) in every mode. Including
    // it here too rendered two "Axis: X" chips stacked at the same TopCenter position whenever
    // the axis was cycled.
    val showFeedback = state.gestureInProgress
    val activeLayer = state.design
    val isLocked = activeLayer?.isImageLocked ?: false

    if (showFeedback && !isLocked) {
        val axisLabel = when (state.activeRotationAxis) {
            RotationAxis.X -> "Axis: X (Cyan)"
            RotationAxis.Y -> "Axis: Y (Pink)"
            RotationAxis.Z -> "Axis: Z (Green)"
        }

        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 16.dp)
                    .background(Color.DarkGray, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = axisLabel,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
