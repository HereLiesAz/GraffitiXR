
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
fun TraceScreen(viewModel: EditorViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    TraceControls(
        onRotateAxis = viewModel::onCycleRotationAxis,
        onTransformStart = viewModel::onGestureStart,
        onTransform = viewModel::onTransformGesture,
        onTransformEnd = viewModel::onGestureEnd,
        axisName = uiState.activeRotationAxis.name
    )
}

@Composable
fun TraceControls(
    onRotateAxis: () -> Unit,
    onTransformStart: () -> Unit,
    onTransform: (Offset, Float, Float) -> Unit,
    onTransformEnd: () -> Unit,
    axisName: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onTransformStart()
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                    onTransformEnd()
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, rotation ->
                    onTransform(pan, zoom, rotation)
                }
            }
    ) {
        Button(
            onClick = onRotateAxis,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            Text("Rotate Axis: $axisName")
        }
    }
}
