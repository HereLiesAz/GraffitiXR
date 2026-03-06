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

@Composable
fun TargetCreationBackground(
    uiState: ArUiState,
    captureStep: CaptureStep,
    onInitUnwarpPoints: (List<Offset>) -> Unit
) {
    LaunchedEffect(uiState.tempCaptureBitmap) {
        if (uiState.unwarpPoints.isEmpty() && uiState.tempCaptureBitmap != null) {
            val points = listOf(
                Offset(0.2f, 0.2f),
                Offset(0.8f, 0.2f),
                Offset(0.8f, 0.8f),
                Offset(0.2f, 0.8f)
            )
            onInitUnwarpPoints(points)
        }
    }

    when (captureStep) {
        CaptureStep.CAPTURE -> {
            TargetCreationOverlayBackground(uiState, CaptureStep.CAPTURE)
        }
        CaptureStep.RECTIFY -> {
            UnwarpBackground(
                targetImage = uiState.tempCaptureBitmap,
                points = uiState.unwarpPoints,
                activePointIndex = uiState.activeUnwarpPointIndex,
                onPointIndexChanged = { },
                onMagnifierPositionChanged = { }
            )
        }
        CaptureStep.MASK -> {
            MaskingBackground(
                targetImage = uiState.tempCaptureBitmap,
                maskPath = uiState.maskPath ?: Path(),
                currentPath = null
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
                    val src = uiState.maskPath ?: Path()
                    val newPath = Path()
                    newPath.addPath(src)
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