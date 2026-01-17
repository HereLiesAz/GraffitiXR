package com.hereliesaz.graffitixr

import android.graphics.Bitmap
import android.net.Uri
import android.os.Parcelable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import com.google.ar.core.AugmentedImageDatabase
import com.hereliesaz.graffitixr.data.CalibrationSnapshot
import com.hereliesaz.graffitixr.data.GithubRelease
import com.hereliesaz.graffitixr.data.OverlayLayer
import com.hereliesaz.graffitixr.data.RefinementPath
import com.hereliesaz.graffitixr.utils.BlendModeParceler
import com.hereliesaz.graffitixr.utils.DrawingPathsParceler
import com.hereliesaz.graffitixr.utils.OffsetListParceler
import com.hereliesaz.graffitixr.utils.OffsetParceler
import com.hereliesaz.graffitixr.utils.TapFeedbackParceler
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith

/**
 * Represents the current state of the AR Target Creation process.
 */
@Parcelize
enum class TargetCreationState : Parcelable {
    IDLE,
    CREATING,
    SAVING,
    SUCCESS,
    ERROR
}

@Parcelize
enum class TargetCreationMode : Parcelable {
    CAPTURE,
    GUIDED_GRID,
    GUIDED_POINTS,
    MULTI_POINT_CALIBRATION,
    RECTIFY
}

/**
 * Represents the specific step in the multi-angle capture process for AR targets.
 */
@Parcelize
enum class CaptureStep : Parcelable {
    ADVICE,
    CHOOSE_METHOD,
    GRID_CONFIG,
    GUIDED_CAPTURE,
    INSTRUCTION,
    FRONT,
    LEFT,
    RIGHT,
    UP,
    DOWN,
    REVIEW,
    ASK_GPS,
    CALIBRATION_POINT_1,
    CALIBRATION_POINT_2,
    CALIBRATION_POINT_3,
    CALIBRATION_POINT_4,
    PHOTO_SEQUENCE,
    RECTIFY
}

/**
 * The single source of truth for the application's UI state.
 */
@Parcelize
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
    val offset: @WriteWith<OffsetParceler> Offset = Offset.Zero,
    val opacity: Float = 1f,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val colorBalanceR: Float = 1f,
    val colorBalanceG: Float = 1f,
    val colorBalanceB: Float = 1f,
    val curvesPoints: @WriteWith<OffsetListParceler> List<Offset> = emptyList(),
    val processedImageUri: Uri? = null,
    val blendMode: @WriteWith<BlendModeParceler> BlendMode = BlendMode.SrcOver,

    // Default is Z (Spin) because geometry is X-Y projected to X-Z
    val activeRotationAxis: RotationAxis = RotationAxis.Z,

    val isToolbarVisible: Boolean = true,
    val isSettingsPanelVisible: Boolean = false,
    val isImageSelectionMode: Boolean = false,
    val showImagePicker: Boolean = false,
    val backgroundImageUri: Uri? = null,
    val overlayImageUri: Uri? = null,
    val originalOverlayImageUri: Uri? = null,
    val backgroundRemovedImageUri: Uri? = null,

    // Multi-Layer Support
    val layers: List<OverlayLayer> = emptyList(),
    val activeLayerId: String? = null,
    val isLineDrawing: Boolean = false,
    val isBackgroundRemovalEnabled: Boolean = false,
    val isBackgroundRemovalLoading: Boolean = false,
    val backgroundRemovalError: String? = null,
    val arState: ArState = ArState.SEARCHING,
    val targetCreationState: TargetCreationState = TargetCreationState.IDLE,
    val isArPlanesDetected: Boolean = false,
    val isArTargetCreated: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val showGestureFeedback: Boolean = false,
    val showRotationAxisFeedback: Boolean = false,
    val showDoubleTapHint: Boolean = false,
    val tapFeedback: @WriteWith<TapFeedbackParceler> TapFeedback? = null,
    val fingerprintJson: String? = null,
    val isMarkingProgress: Boolean = false,
    val drawingPaths: @WriteWith<DrawingPathsParceler> List<List<Pair<Float, Float>>> = emptyList(),
    val progressPercentage: Float = 0f,
    val isCapturingTarget: Boolean = false,
    val isTouchLocked: Boolean = false,
    val showUnlockInstructions: Boolean = false,
    val hideUiForCapture: Boolean = false,
    val isImageLocked: Boolean = false,

    val updateStatusMessage: String? = null,
    val isCheckingForUpdate: Boolean = false,
    val latestRelease: GithubRelease? = null,
    val isFlashlightOn: Boolean = false,
    val isGpsMarkingEnabled: Boolean = false,

    // Multi-step Capture State
    val targetCreationMode: TargetCreationMode = TargetCreationMode.CAPTURE,
    val captureStep: CaptureStep = CaptureStep.ADVICE,
    val gridRows: Int = 2,
    val gridCols: Int = 2,
    val isGridGuideVisible: Boolean = false,
    val qualityWarning: String? = null,
    val captureFailureTimestamp: Long = 0L,
    val capturedTargetUris: List<Uri> = emptyList(),
    @IgnoredOnParcel
    val capturedTargetImages: List<Bitmap> = emptyList(),

    // Evolution History
    val evolutionCaptureUris: List<Uri> = emptyList(),

    // Refinement State
    val refinementPaths: List<RefinementPath> = emptyList(),
    val isRefinementEraser: Boolean = false,
    val detectedKeypoints: @WriteWith<OffsetListParceler> List<Offset> = emptyList(),
    val targetMaskUri: Uri? = null,

    // Calibration Snapshots (GPS + Sensor + Pose)
    val calibrationSnapshots: List<CalibrationSnapshot> = emptyList(),

    @IgnoredOnParcel
    val augmentedImageDatabase: AugmentedImageDatabase? = null,

    // --- Neural Scan / Mapping State ---
    val isMappingMode: Boolean = false,
    val mappingQualityScore: Float = 0f, // 0.0 to 1.0 (Mapped from FeatureMapQuality)
    val isHostingAnchor: Boolean = false,
    val cloudAnchorId: String? = null
) : Parcelable