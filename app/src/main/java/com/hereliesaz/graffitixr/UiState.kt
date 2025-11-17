package com.hereliesaz.graffitixr

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode


enum class RotationAxis { X, Y, Z }
enum class TargetCreationState { IDLE, CREATING, SUCCESS, ERROR }

data class UiState(
    val editorMode: EditorMode = EditorMode.STATIC,
    val completedOnboardingModes: Set<EditorMode> = emptySet(),
    val showOnboardingDialogForMode: EditorMode? = null,
    val isLoading: Boolean = false,
    val scale: Float = 1f,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,
    val arObjectScale: Float = 1f,
    val offset: Offset = Offset.Zero,
    val opacity: Float = 1f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val colorBalanceR: Float = 1f,
    val colorBalanceG: Float = 1f,
    val colorBalanceB: Float = 1f,
    val curvesPoints: List<Offset> = emptyList(),
    val processedImageUri: Uri? = null,
    val blendMode: BlendMode = BlendMode.SrcOver,
    val activeRotationAxis: RotationAxis = RotationAxis.X,
    val isToolbarVisible: Boolean = true,
    val isSettingsPanelVisible: Boolean = false,
    val isImageSelectionMode: Boolean = false,
    val backgroundImageUri: Uri? = null,
    val overlayImageUri: Uri? = null,
    val backgroundRemovedImageUri: Uri? = null,
    val isBackgroundRemovalEnabled: Boolean = false,
    val isBackgroundRemovalLoading: Boolean = false,
    val backgroundRemovalError: String? = null,
    val targetCreationState: TargetCreationState = TargetCreationState.IDLE,
    val isArPlanesDetected: Boolean = false,
    val isArTargetCreated: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val showGestureFeedback: Boolean = false,
    val showRotationAxisFeedback: Boolean = false,
    val showDoubleTapHint: Boolean = false,
    val tapFeedback: TapFeedback? = null,
    val refinementImageUri: Uri? = null,
    val fingerprintJson: String? = null,
    val isMarkingProgress: Boolean = false,
    val drawingPaths: List<List<Pair<Float, Float>>> = emptyList(),
    val progressPercentage: Float = 0f
)
