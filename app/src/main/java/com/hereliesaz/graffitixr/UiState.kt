package com.hereliesaz.graffitixr

import android.graphics.Bitmap
import android.graphics.Path
import android.net.Uri
import com.hereliesaz.graffitixr.data.ArState
import com.hereliesaz.graffitixr.data.OverlayLayer
import com.hereliesaz.graffitixr.data.RotationAxis

enum class EditorMode { STATIC, OVERLAY, TRACE, AR }
enum class CaptureStep { PREVIEW, CAPTURE, REVIEW, RECTIFY, CALIBRATION_POINT_1, CALIBRATION_POINT_2, FINISHING }
enum class TargetCreationMode { GUIDED_GRID, GUIDED_POINTS, SINGLE_IMAGE }

sealed class FeedbackEvent {
    object VibrateSingle : FeedbackEvent()
    object VibrateDouble : FeedbackEvent()
}

sealed class CaptureEvent {
    object RequestCapture : CaptureEvent()
}

data class UiState(
    // Mode & Global
    val editorMode: EditorMode = EditorMode.AR,
    val showImagePicker: Boolean = false,
    val isLoading: Boolean = false,
    val hideUiForCapture: Boolean = false,
    val updateStatusMessage: String? = null,
    val isCheckingForUpdate: Boolean = false,

    // Layers & Images
    val layers: List<OverlayLayer> = emptyList(),
    val activeLayerId: String? = null,
    val overlayImageUri: Uri? = null, // Legacy/Fallback
    val backgroundImageUri: Uri? = null,
    val isImageLocked: Boolean = false,

    // AR State
    val arState: ArState = ArState.SEARCHING,
    val isArPlanesDetected: Boolean = false,
    val isArTargetCreated: Boolean = false,
    val qualityWarning: String? = null,
    val mappingQualityScore: Float = 0f,
    val isMappingMode: Boolean = false,
    val isHostingAnchor: Boolean = false,
    val fingerprintJson: String? = null,

    // Target Capture / Creation
    val isCapturingTarget: Boolean = false,
    val captureStep: CaptureStep = CaptureStep.PREVIEW,
    val targetCreationMode: TargetCreationMode = TargetCreationMode.GUIDED_GRID,
    val capturedTargetUris: List<Uri> = emptyList(),
    val capturedTargetImages: List<Bitmap> = emptyList(),
    val targetMaskUri: Uri? = null,
    val gridRows: Int = 3,
    val gridCols: Int = 3,
    val isGridGuideVisible: Boolean = false,
    val captureFailureTimestamp: Long = 0L,

    // Tools & Refinement
    val isMarkingProgress: Boolean = false,
    val progressPercentage: Float = 0f,
    val drawingPaths: List<Path> = emptyList(),
    val refinementPaths: List<Path> = emptyList(),
    val detectedKeypoints: List<Any> = emptyList(),
    val isRefinementEraser: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,

    // Interaction
    val isTouchLocked: Boolean = false,
    val showUnlockInstructions: Boolean = false,
    val showOnboardingDialogForMode: EditorMode? = null,
    val showDoubleTapHint: Boolean = false,
    val activeRotationAxis: RotationAxis = RotationAxis.Y,
    val showRotationAxisFeedback: Boolean = false,

    // Transform values (Global fallback or active layer proxies)
    val opacity: Float = 1.0f,
    val brightness: Float = 0f,
    val scale: Float = 1.0f, // ArObjectScale
    val arObjectScale: Float = 1.0f,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,
    val colorBalanceR: Float = 1.0f,
    val colorBalanceG: Float = 1.0f,
    val colorBalanceB: Float = 1.0f
)