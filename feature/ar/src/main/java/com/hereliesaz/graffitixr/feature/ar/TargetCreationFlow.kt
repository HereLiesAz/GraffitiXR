package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.CaptureStep
import com.hereliesaz.graffitixr.feature.ar.masking.MaskingBackground
import com.hereliesaz.graffitixr.feature.ar.masking.MaskingUi
import com.hereliesaz.graffitixr.feature.ar.masking.applyAutoMask
import kotlinx.coroutines.launch

/**
 * State object for Target Creation Flow to share state between Background and UI.
 */
class TargetCreationState(
    val unwarpPoints: MutableList<Offset>,
    var activeUnwarpPointIndex: Int,
    var magnifierPosition: Offset,
    val maskPath: Path,
    var currentMaskPath: Path?,
    val cameraController: CameraController
)

@Composable
fun rememberTargetCreationState(): TargetCreationState {
    val unwarpPoints = remember { mutableStateListOf<Offset>() }
    var activeUnwarpPointIndex by remember { mutableStateOf(-1) }
    var magnifierPosition by remember { mutableStateOf(Offset.Zero) }
    val maskPath by remember { mutableStateOf(Path()) }
    var currentMaskPath by remember { mutableStateOf<Path?>(null) }
    val cameraController = rememberCameraController()

    return remember {
        TargetCreationState(
            unwarpPoints,
            activeUnwarpPointIndex,
            magnifierPosition,
            maskPath,
            currentMaskPath,
            cameraController
        )
    }.apply {
        this.activeUnwarpPointIndex = activeUnwarpPointIndex
        this.magnifierPosition = magnifierPosition
        this.currentMaskPath = currentMaskPath
    }
}

@Composable
fun TargetCreationBackground(
    uiState: ArUiState,
    captureStep: CaptureStep,
    state: TargetCreationState,
    onPhotoCaptured: (Bitmap) -> Unit
) {
    // Initialize unwarp points when image changes
    LaunchedEffect(uiState.tempCaptureBitmap) {
        if (state.unwarpPoints.isEmpty() && uiState.tempCaptureBitmap != null) {
            state.unwarpPoints.add(Offset(0.2f, 0.2f)) // TL
            state.unwarpPoints.add(Offset(0.8f, 0.2f)) // TR
            state.unwarpPoints.add(Offset(0.8f, 0.8f)) // BR
            state.unwarpPoints.add(Offset(0.2f, 0.8f)) // BL
        }
    }

    when (captureStep) {
        CaptureStep.CAPTURE -> {
            CameraPreview(
                controller = state.cameraController,
                onPhotoCaptured = onPhotoCaptured
            )
            TargetCreationOverlayBackground(uiState, CaptureStep.CAPTURE)
        }
        CaptureStep.RECTIFY -> {
            UnwarpBackground(
                targetImage = uiState.tempCaptureBitmap,
                points = state.unwarpPoints,
                activePointIndex = state.activeUnwarpPointIndex,
                onPointIndexChanged = { state.activeUnwarpPointIndex = it },
                onMagnifierPositionChanged = { state.magnifierPosition = it }
            )
        }
        CaptureStep.MASK -> {
            MaskingBackground(
                targetImage = uiState.tempCaptureBitmap,
                maskPath = state.maskPath,
                currentPath = state.currentMaskPath,
                onPathStarted = { state.currentMaskPath = Path().apply { moveTo(it.x, it.y) } },
                onPathFinished = {
                    state.currentMaskPath?.let { state.maskPath.addPath(it) }
                    state.currentMaskPath = null
                },
                onPathDragged = { state.currentMaskPath?.relativeLineTo(it.x, it.y) }
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
    state: TargetCreationState,
    onConfirm: () -> Unit,
    onRetake: () -> Unit,
    onCancel: () -> Unit,
    onUnwarpConfirm: (List<Offset>) -> Unit,
    onMaskConfirmed: (Bitmap) -> Unit
) {
    val scope = rememberCoroutineScope()
    var isProcessingMask by remember { mutableStateOf(false) }

    when (captureStep) {
        CaptureStep.CAPTURE -> {
            TargetCreationOverlayUi(
                uiState = uiState,
                step = CaptureStep.CAPTURE,
                onPrimaryAction = { state.cameraController.takePicture() },
                onCancel = onCancel
            )
        }
        CaptureStep.RECTIFY -> {
            UnwarpUi(
                isRightHanded = isRightHanded,
                targetImage = uiState.tempCaptureBitmap,
                points = state.unwarpPoints,
                activePointIndex = state.activeUnwarpPointIndex,
                magnifierPosition = state.magnifierPosition,
                onConfirm = onUnwarpConfirm,
                onRetake = onRetake
            )
        }
        CaptureStep.MASK -> {
            MaskingUi(
                targetImage = uiState.tempCaptureBitmap,
                isProcessing = isProcessingMask,
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
