package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.CaptureStep

@Composable
fun TargetCreationFlow(
    uiState: ArUiState,
    isRightHanded: Boolean,
    captureStep: CaptureStep,
    context: Context,
    onConfirm: () -> Unit,
    onRetake: () -> Unit,
    onCancel: () -> Unit,
    onCaptureShutter: () -> Unit,
    onCalibrationPointCaptured: (Offset) -> Unit,
    onUnwarpImage: (List<Offset>) -> Unit
) {
    // This composable manages the UI overlays for the target creation process
    // (Capture -> Rectify -> Review)

    when (captureStep) {
        CaptureStep.CAPTURE -> {
            // Camera Overlay is handled by ArView, we just need the shutter button
            TargetCreationOverlay(
                uiState = uiState,
                step = CaptureStep.CAPTURE,
                onPrimaryAction = onCaptureShutter,
                onCancel = onCancel
            )
        }
        CaptureStep.RECTIFY -> {
            // Show the captured bitmap and allow corner dragging
            // For now, assuming rectification logic is handled by a dedicated view or dialog
            // invoking onUnwarpImage when done.
            TargetCreationOverlay(
                uiState = uiState,
                step = CaptureStep.RECTIFY,
                onPrimaryAction = {
                    // Mock: just pass 4 corners of the screen/image
                    onUnwarpImage(listOf(Offset(0f,0f), Offset(100f,0f), Offset(100f,100f), Offset(0f,100f)))
                },
                onCancel = onRetake
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