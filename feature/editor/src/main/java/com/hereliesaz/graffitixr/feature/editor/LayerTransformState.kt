package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.data.OverlayLayer

@Stable
class LayerTransformState(
    initialScale: Float,
    initialOffset: Offset,
    initialRotationX: Float,
    initialRotationY: Float,
    initialRotationZ: Float
) {
    var isGesturing by mutableStateOf(false)
    var scale by mutableFloatStateOf(initialScale)
    var offset by mutableStateOf(initialOffset)
    var rotationX by mutableFloatStateOf(initialRotationX)
    var rotationY by mutableFloatStateOf(initialRotationY)
    var rotationZ by mutableFloatStateOf(initialRotationZ)
}

@Composable
fun rememberLayerTransformState(
    activeLayer: OverlayLayer?
): LayerTransformState {
    val state = remember {
        LayerTransformState(
            initialScale = activeLayer?.scale ?: 1f,
            initialOffset = activeLayer?.offset ?: Offset.Zero,
            initialRotationX = activeLayer?.rotationX ?: 0f,
            initialRotationY = activeLayer?.rotationY ?: 0f,
            initialRotationZ = activeLayer?.rotationZ ?: 0f
        )
    }

    LaunchedEffect(activeLayer) {
        if (!state.isGesturing && activeLayer != null) {
            state.scale = activeLayer.scale
            state.offset = activeLayer.offset
            state.rotationX = activeLayer.rotationX
            state.rotationY = activeLayer.rotationY
            state.rotationZ = activeLayer.rotationZ
        }
    }

    return state
}
