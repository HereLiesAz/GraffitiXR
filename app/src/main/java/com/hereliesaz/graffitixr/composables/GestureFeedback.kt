package com.hereliesaz.graffitixr.composables

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.UiState

@Composable
fun GestureFeedback(
    uiState: UiState,
    modifier: Modifier = Modifier,
    isVisible: Boolean
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            val rotationValue = when (uiState.activeRotationAxis) {
                com.hereliesaz.graffitixr.RotationAxis.X -> uiState.rotationX
                com.hereliesaz.graffitixr.RotationAxis.Y -> uiState.rotationY
                com.hereliesaz.graffitixr.RotationAxis.Z -> uiState.rotationZ
            }
            Text(
                text = "Scale: %.2f, Rotation (%s): %.1f°".format(
                    uiState.scale,
                    uiState.activeRotationAxis.name,
                    rotationValue
                ),
                color = Color.White,
                modifier = Modifier.shadow(elevation = 2.dp)
            )
        }
    }
}
