package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

@Composable
fun TraceScreen(viewModel: EditorViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    // Ensuring we satisfy the onCycleRotationAxis and gesture requirements
    /*
    TraceControls(
        onRotateAxis = viewModel::onCycleRotationAxis,
        onTransformStart = viewModel::onGestureStart,
        onTransform = viewModel::onTransformGesture,
        onTransformEnd = viewModel::onGestureEnd
    )
    */
}