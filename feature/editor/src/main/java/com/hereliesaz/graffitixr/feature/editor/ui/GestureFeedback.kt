package com.hereliesaz.graffitixr.feature.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.RotationAxis

@Composable
fun GestureFeedback(
    uiState: EditorUiState,
    modifier: Modifier = Modifier
) {
    // Show feedback if gesture is in progress, axis change was requested, OR editing is unlocked
    val isVisible = uiState.gestureInProgress || uiState.showRotationAxisFeedback || !uiState.isImageLocked

    if (isVisible) {
        Box(
            modifier = modifier
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }

            if (activeLayer != null) {
                val text = when {
                    uiState.showRotationAxisFeedback || !uiState.isImageLocked -> {
                        val axisName = when(uiState.activeRotationAxis) {
                            RotationAxis.X -> "X-Axis"
                            RotationAxis.Y -> "Y-Axis"
                            RotationAxis.Z -> "Z-Axis"
                        }
                        if (uiState.gestureInProgress) {
                             val scalePct = (activeLayer.scale * 100).toInt()
                             "Rotation: $axisName | Scale: ${scalePct}%"
                        } else {
                             "Rotation: $axisName"
                        }
                    }
                    else -> {
                        val scalePct = (activeLayer.scale * 100).toInt()
                        val rotX = activeLayer.rotationX.toInt() % 360
                        val rotY = activeLayer.rotationY.toInt() % 360
                        val rotZ = activeLayer.rotationZ.toInt() % 360

                        "Scale: ${scalePct}%  |  Rot: ${rotX}°, ${rotY}°, ${rotZ}°"
                    }
                }
                Text(text = text, color = Color.White, fontSize = 14.sp)
            }
        }
    }
}