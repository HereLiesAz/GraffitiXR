package com.hereliesaz.graffitixr.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.RotationAxis
import com.hereliesaz.graffitixr.UiState

@Composable
fun GestureBox(
    uiState: UiState,
    onScaleChanged: (Float) -> Unit,
    onOffsetChanged: (Offset) -> Unit,
    onRotationZChanged: (Float) -> Unit,
    onRotationXChanged: (Float) -> Unit,
    onRotationYChanged: (Float) -> Unit,
    onCycleRotationAxis: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var gestureInProgress by remember { mutableStateOf(false) }

    val transformState = rememberTransformableState { zoomChange, offsetChange, rotationChange ->
        onScaleChanged(zoomChange)
        onOffsetChanged(offsetChange)
        when (uiState.activeRotationAxis) {
            RotationAxis.X -> onRotationXChanged(rotationChange)
            RotationAxis.Y -> onRotationYChanged(rotationChange)
            RotationAxis.Z -> onRotationZChanged(rotationChange)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { gestureInProgress = true },
                    onDragEnd = { gestureInProgress = false }
                ) { _, _ -> }
            }
            .transformable(state = transformState)
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onCycleRotationAxis() })
            }
    ) {
        content()
        if (gestureInProgress || transformState.isTransformInProgress) {
            val rotationValue = when (uiState.activeRotationAxis) {
                RotationAxis.X -> uiState.rotationX
                RotationAxis.Y -> uiState.rotationY
                RotationAxis.Z -> uiState.rotationZ
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Scale: %.2f, Rotation (%s): %.1f°".format(uiState.scale, uiState.activeRotationAxis.name, rotationValue),
                    color = Color.White
                )
            }
        }
    }
}
