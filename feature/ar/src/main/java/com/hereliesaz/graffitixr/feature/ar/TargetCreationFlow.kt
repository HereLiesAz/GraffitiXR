package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.CaptureStep

/**
 * Manages the UI flow for creating a new AR target (fingerprint).
 *
 * This composable acts as a state machine controller, switching between:
 * 1. [CaptureStep.CAPTURE]: Showing the camera feed and a shutter button.
 * 2. [CaptureStep.RECTIFY]: Showing the captured image with 4 draggable corners to unwarp perspective.
 * 3. [CaptureStep.REVIEW]: Showing the final unwarped target for confirmation.
 *
 * @param uiState Current AR UI state containing the captured bitmap.
 * @param captureStep The current step in the creation process.
 * @param onConfirm Callback when the user accepts the final target.
 * @param onRetake Callback to restart the capture process.
 * @param onCancel Callback to exit the flow completely.
 * @param onPhotoCaptured Callback with the captured bitmap.
 * @param onUnwarpImage Callback with the 4 corner points to perform rectification.
 */
@Composable
fun TargetCreationFlow(
    uiState: ArUiState,
    isRightHanded: Boolean,
    captureStep: CaptureStep,
    context: Context,
    onConfirm: () -> Unit,
    onRetake: () -> Unit,
    onCancel: () -> Unit,
    onPhotoCaptured: (Bitmap) -> Unit,
    onCalibrationPointCaptured: (Offset) -> Unit,
    onUnwarpImage: (List<Offset>) -> Unit
) {
    val cameraController = rememberCameraController()

    when (captureStep) {
        CaptureStep.CAPTURE -> {
            Box(Modifier.fillMaxSize()) {
                // Background Camera Preview
                CameraPreview(
                    controller = cameraController,
                    onPhotoCaptured = onPhotoCaptured
                )

                TargetCreationOverlay(
                    uiState = uiState,
                    step = CaptureStep.CAPTURE,
                    onPrimaryAction = { cameraController.takePicture() },
                    onCancel = onCancel
                )
            }
        }
        CaptureStep.RECTIFY -> {
            UnwarpScreen(
                isRightHanded = isRightHanded,
                targetImage = uiState.tempCaptureBitmap,
                onConfirm = onUnwarpImage,
                onRetake = onRetake
            )
        }
        CaptureStep.REVIEW -> {
            TargetCreationOverlay(
                uiState = uiState,
                step = CaptureStep.REVIEW,
                onPrimaryAction = onConfirm,
                onCancel = onRetake
            )
        }
        else -> {}
    }
}
