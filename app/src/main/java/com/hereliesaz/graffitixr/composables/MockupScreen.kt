package com.hereliesaz.graffitixr.composables

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import com.hereliesaz.graffitixr.RotationAxis
import com.hereliesaz.graffitixr.UiState

/**
 * A composable screen for creating a mock-up of a mural on a static background image.
 *
 * This screen allows the user to select a background and an overlay image. The overlay image can
 * be freely transformed using perspective warp handles, allowing for precise placement and
 * realistic mock-ups. The image properties (opacity, contrast, saturation) are controlled
 * via the main navigation rail.
 *
 * @param uiState The current UI state, containing the URIs for the images and their properties.
 * @param onBackgroundImageSelected A callback to be invoked when the user selects a new background image.
 * @param onOverlayImageSelected A callback to be invoked when the user selects a new overlay image.
 * @param onOpacityChanged A callback to be invoked when the opacity of the overlay is changed.
 * @param onContrastChanged A callback to be invoked when the contrast of the overlay is changed.
 * @param onSaturationChanged A callback to be invoked when the saturation of the overlay is changed.
 * @param onPointsInitialized A callback to initialize the positions of the warp handles.
 */
@Composable
fun MockupScreen(
    uiState: UiState,
    onBackgroundImageSelected: (Uri) -> Unit,
    onOverlayImageSelected: (Uri) -> Unit,
    onOpacityChanged: (Float) -> Unit,
    onContrastChanged: (Float) -> Unit,
    onSaturationChanged: (Float) -> Unit,
    onScaleChanged: (Float) -> Unit,
    onOffsetChanged: (Offset) -> Unit,
    onRotationZChanged: (Float) -> Unit,
    onRotationXChanged: (Float) -> Unit,
    onRotationYChanged: (Float) -> Unit,
    onCycleRotationAxis: () -> Unit,
    onPointsInitialized: (List<Offset>) -> Unit
) {
    val overlayPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> uri?.let { onOverlayImageSelected(it) } }
    )

    val backgroundPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> uri?.let { onBackgroundImageSelected(it) } }
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(uiState.activeRotationAxis) {
                detectTransformGestures { _, pan, zoom, rotation ->
                    onScaleChanged(zoom)
                    onOffsetChanged(pan)
                    when (uiState.activeRotationAxis) {
                        RotationAxis.X -> onRotationXChanged(rotation)
                        RotationAxis.Y -> onRotationYChanged(rotation)
                        RotationAxis.Z -> onRotationZChanged(rotation)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // TODO: Implement MockupScreen content
    }
}
