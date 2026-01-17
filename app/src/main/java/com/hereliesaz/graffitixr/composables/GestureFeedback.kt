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
    val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId } ?: uiState.layers.firstOrNull()
    val scale = activeLayer?.scale ?: 1f
    val rotationX = activeLayer?.rotationX ?: 0f
    val rotationY = activeLayer?.rotationY ?: 0f
    val rotationZ = activeLayer?.rotationZ ?: 0f

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
                com.hereliesaz.graffitixr.RotationAxis.X -> rotationX
                com.hereliesaz.graffitixr.RotationAxis.Y -> rotationY
                com.hereliesaz.graffitixr.RotationAxis.Z -> rotationZ
            }
            Text(
                text = "Scale: %.2f, Rotation (%s): %.1f°".format(
                    scale,
                    uiState.activeRotationAxis.name,
                    rotationValue
                ),
                color = Color.White,
                modifier = Modifier.shadow(elevation = 2.dp)
            )
        }
    }
}
