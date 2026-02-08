package com.hereliesaz.graffitixr.common.model

import android.net.Uri
import androidx.compose.ui.geometry.Offset

data class UiState(
    val isLoading: Boolean = false,
    val isTouchLocked: Boolean = false,
    val showUnlockInstructions: Boolean = false,

    // Editor State Bridge
    val layers: List<OverlayLayer> = emptyList(),
    val activeLayerId: String? = null,
    val editorMode: EditorMode = EditorMode.TRANSFORM,
    val isRightHanded: Boolean = true,
    val activeRotationAxis: RotationAxis = RotationAxis.Z,
    val showRotationAxisFeedback: Boolean = false,
    val tapFeedback: Offset? = null,

    // AR State Bridge
    val showPointCloud: Boolean = false,
    val isFlashlightOn: Boolean = false,
    val isCapturingTarget: Boolean = false,
    val isArTargetCreated: Boolean = false,
    val captureStep: CaptureStep = CaptureStep.PREVIEW,
    val targetCreationMode: TargetCreationMode = TargetCreationMode.SINGLE_IMAGE,
    val mappingQualityScore: Float = 0f
)

data class EditorUiState(
    val layers: List<OverlayLayer> = emptyList(),
    val activeLayerId: String? = null,
    val drawingPaths: List<List<Offset>> = emptyList(),
    val editorMode: EditorMode = EditorMode.TRANSFORM,
    val activeRotationAxis: RotationAxis = RotationAxis.Z,
    val showRotationAxisFeedback: Boolean = false,
    val isImageLocked: Boolean = false,
    val sliderDialogType: SliderType? = null, // Custom enum for brightness/contrast sliders
    val showColorBalanceDialog: Boolean = false,
    val gestureInProgress: Boolean = false,
    val showDoubleTapHint: Boolean = false,
    val showOnboardingDialogForMode: Any? = null,
    val isRightHanded: Boolean = true,
    val isLoading: Boolean = false,
    val progressPercentage: Float = 0f
)

data class ArUiState(
    val showPointCloud: Boolean = false,
    val isFlashlightOn: Boolean = false,
    val isArTargetCreated: Boolean = false,
    val mappingQualityScore: Float = 0f,
    val capturedTargetUris: List<Uri> = emptyList()
)

// Enums required for the state
enum class EditorMode { TRANSFORM, DRAW, ERASE }
enum class RotationAxis { X, Y, Z }
enum class CaptureStep { PREVIEW, CAPTURE, CONFIRM }
enum class TargetCreationMode { SINGLE_IMAGE, GUIDED_GRID, SLAM_SCAN }
enum class SliderType { OPACITY, SCALE, ROTATION }