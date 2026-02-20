package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.CaptureStep
import com.hereliesaz.graffitixr.feature.ar.masking.MaskingBackground
import com.hereliesaz.graffitixr.feature.ar.masking.MaskingUi
import com.hereliesaz.graffitixr.feature.ar.masking.applyAutoMask
import kotlinx.coroutines.launch

/**
 * Manages the UI flow for creating a new AR target (fingerprint).
 *
 * This composable acts as a state machine controller, switching between:
 * 1. [CaptureStep.CAPTURE]: Showing the camera feed and a shutter button.
 * 2. [CaptureStep.RECTIFY]: Showing the captured image with 4 draggable corners to unwarp perspective.
 * 3. [CaptureStep.MASK]: Masking the image.
 * 4. [CaptureStep.REVIEW]: Showing the final unwarped target for confirmation.
 */

@Composable
fun TargetCreationBackground(
    uiState: ArUiState,
    captureStep: CaptureStep,
    onPhotoCaptured: (Bitmap) -> Unit,
    onCaptureConsumed: () -> Unit,
    onInitUnwarpPoints: (List<Offset>) -> Unit
) {
    val cameraController = rememberCameraController()

    // Handle capture request
    LaunchedEffect(uiState.isCaptureRequested) {
        if (uiState.isCaptureRequested) {
            cameraController.takePicture()
        }
    }

    // Initialize unwarp points when image changes
    LaunchedEffect(uiState.tempCaptureBitmap) {
        if (uiState.unwarpPoints.isEmpty() && uiState.tempCaptureBitmap != null) {
            val points = listOf(
                Offset(0.2f, 0.2f), // TL
                Offset(0.8f, 0.2f), // TR
                Offset(0.8f, 0.8f), // BR
                Offset(0.2f, 0.8f)  // BL
            )
            onInitUnwarpPoints(points)
        }
    }

    when (captureStep) {
        CaptureStep.CAPTURE -> {
            CameraPreview(
                controller = cameraController,
                onPhotoCaptured = {
                    onPhotoCaptured(it)
                    onCaptureConsumed()
                }
            )
            TargetCreationOverlayBackground(uiState, CaptureStep.CAPTURE)
        }
        CaptureStep.RECTIFY -> {
            UnwarpBackground(
                targetImage = uiState.tempCaptureBitmap,
                points = uiState.unwarpPoints,
                activePointIndex = uiState.activeUnwarpPointIndex,
                onPointIndexChanged = { /* Background doesn't handle input */ },
                onMagnifierPositionChanged = { /* Background doesn't handle input */ }
            )
        }
        CaptureStep.MASK -> {
            MaskingBackground(
                targetImage = uiState.tempCaptureBitmap,
                maskPath = uiState.maskPath ?: Path(),
                currentPath = null // UI handles live drawing
            )
        }
        CaptureStep.REVIEW -> {
            TargetCreationOverlayBackground(uiState, CaptureStep.REVIEW)
        }
        else -> {}
    }
}

@Composable
fun TargetCreationUi(
    uiState: ArUiState,
    isRightHanded: Boolean,
    captureStep: CaptureStep,
    onConfirm: () -> Unit,
    onRetake: () -> Unit,
    onCancel: () -> Unit,
    onUnwarpConfirm: (List<Offset>) -> Unit,
    onMaskConfirmed: (Bitmap) -> Unit,
    onRequestCapture: () -> Unit,
    onUpdateUnwarpPoints: (List<Offset>) -> Unit,
    onSetActiveUnwarpPoint: (Int) -> Unit,
    onSetMagnifierPosition: (Offset) -> Unit,
    onUpdateMaskPath: (Path?) -> Unit
) {
    val scope = rememberCoroutineScope()
    var isProcessingMask by remember { mutableStateOf(false) }
    // Local state for live mask drawing
    var currentMaskPath by remember { mutableStateOf<Path?>(null) }

    when (captureStep) {
        CaptureStep.CAPTURE -> {
            Box(Modifier.fillMaxSize()) {
                TargetCreationOverlayUi(
                    uiState = uiState,
                    step = CaptureStep.CAPTURE,
                    onPrimaryAction = onRequestCapture,
                    onCancel = onCancel
                )
            }
        }
        CaptureStep.RECTIFY -> {
            UnwarpUi(
                isRightHanded = isRightHanded,
                targetImage = uiState.tempCaptureBitmap,
                points = uiState.unwarpPoints,
                activePointIndex = uiState.activeUnwarpPointIndex,
                magnifierPosition = uiState.magnifierPosition,
                onPointIndexChanged = onSetActiveUnwarpPoint,
                onPointMoved = { index, offset ->
                    val newPoints = uiState.unwarpPoints.toMutableList()
                    if (index in newPoints.indices) {
                        newPoints[index] = offset
                        onUpdateUnwarpPoints(newPoints)
                    }
                },
                onMagnifierPositionChanged = onSetMagnifierPosition,
                onConfirm = onUnwarpConfirm,
                onRetake = onRetake
            )
        }
        CaptureStep.MASK -> {
            MaskingUi(
                targetImage = uiState.tempCaptureBitmap,
                isProcessing = isProcessingMask,
                maskPath = uiState.maskPath ?: Path(),
                currentPath = currentMaskPath,
                onPathStarted = {
                    currentMaskPath = Path().apply { moveTo(it.x, it.y) }
                },
                onPathFinished = {
                    val newPath = Path(uiState.maskPath ?: Path())
                    currentMaskPath?.let { newPath.addPath(it) }
                    currentMaskPath = null
                    onUpdateMaskPath(newPath)
                },
                onPathDragged = {
                    currentMaskPath?.relativeLineTo(it.x, it.y)
                },
                onConfirm = onMaskConfirmed,
                onRetake = onRetake,
                onAutoMask = {
                    uiState.tempCaptureBitmap?.let { bitmap ->
                        scope.launch {
                            isProcessingMask = true
                            val masked = applyAutoMask(bitmap)
                            isProcessingMask = false
                            if (masked != null) onMaskConfirmed(masked)
                        }
                    }
                }
            )
        }
        CaptureStep.REVIEW -> {
            TargetCreationOverlayUi(
                uiState = uiState,
                step = CaptureStep.REVIEW,
                onPrimaryAction = onConfirm,
                onCancel = onRetake
            )
        }
        else -> {}
    }
}
