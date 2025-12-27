package com.hereliesaz.graffitixr

import android.graphics.Bitmap
import android.net.Uri
import android.os.Parcelable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import com.google.ar.core.AugmentedImageDatabase
import com.hereliesaz.graffitixr.data.GithubRelease
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
    MULTI_POINT_CALIBRATION
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
    CALIBRATION_POINT_4
}

/**
 * The single source of truth for the application's UI state.
 * This immutable data class holds all the data required to render the UI,
 * enabling a unidirectional data flow pattern.
 *
 * @property editorMode The current operational mode of the editor (e.g., AR, STATIC, OVERLAY).
 * @property completedOnboardingModes A set of modes for which the user has completed the onboarding tutorial.
 * @property showOnboardingDialogForMode If non-null, indicates that the onboarding dialog should be shown for this mode.
 * @property isLoading Indicates whether a background operation is in progress (showing a loading spinner).
 * @property scale The scale factor of the overlay image.
 * @property rotationX The rotation of the overlay image around the X-axis (in degrees).
 * @property rotationY The rotation of the overlay image around the Y-axis (in degrees).
 * @property rotationZ The rotation of the overlay image around the Z-axis (in degrees).
 * @property arObjectScale The scale factor of the AR object in world space.
 * @property offset The 2D offset of the overlay image (used in non-AR modes).
 * @property opacity The opacity (alpha) of the overlay image (0f to 1f).
 * @property brightness The brightness adjustment factor (-1f to 1f).
 * @property contrast The contrast adjustment factor (0f to 2f).
 * @property saturation The saturation adjustment factor (0f to 2f).
 * @property colorBalanceR The red color balance adjustment (0f to 2f).
 * @property colorBalanceG The green color balance adjustment (0f to 2f).
 * @property colorBalanceB The blue color balance adjustment (0f to 2f).
 * @property curvesPoints A list of points defining the color curve adjustment.
 * @property processedImageUri The URI of the image after applying advanced processing (like curves).
 * @property blendMode The blending mode used to composite the overlay image.
 * @property activeRotationAxis The currently selected axis for rotation adjustments.
 * @property isToolbarVisible Whether the main toolbar is currently visible.
 * @property isSettingsPanelVisible Whether the settings panel is currently visible.
 * @property isImageSelectionMode Whether the user is currently selecting an image.
 * @property backgroundImageUri The URI of the background image (for Static/Mockup mode).
 * @property overlayImageUri The URI of the current overlay image.
 * @property originalOverlayImageUri The URI of the original, unmodified overlay image.
 * @property backgroundRemovedImageUri The URI of the overlay image with background removed.
 * @property isLineDrawing Whether the overlay image is currently converted to a line drawing.
 * @property isBackgroundRemovalEnabled Whether background removal is active.
 * @property isBackgroundRemovalLoading Whether background removal is currently processing.
 * @property backgroundRemovalError Error message related to background removal, if any.
 * @property arState The current state of the AR session (SEARCHING, LOCKED, PLACED).
 * @property targetCreationState The state of the AR target creation process.
 * @property isArPlanesDetected Whether AR planes have been detected by ARCore.
 * @property isArTargetCreated Whether an AR target has been successfully created.
 * @property canUndo Whether an undo operation is available.
 * @property canRedo Whether a redo operation is available.
 * @property showGestureFeedback Whether to show visual feedback for gestures.
 * @property showRotationAxisFeedback Whether to show feedback for rotation axis changes.
 * @property showDoubleTapHint Whether to show a hint about double-tapping to reset.
 * @property tapFeedback Information about the last tap interaction (success/failure, position).
 * @property fingerprintJson The serialized JSON string of the AR target's OpenCV fingerprint.
 * @property isMarkingProgress Whether the "Mark Progress" feature is active.
 * @property drawingPaths Paths drawn by the user for progress marking.
 * @property progressPercentage The calculated completion percentage of the artwork.
 * @property isCapturingTarget Whether the app is currently capturing frames for a target.
 * @property isTouchLocked Whether touch interactions are currently disabled.
 * @property hideUiForCapture Whether to hide the UI for a clean screenshot.
 * @property updateStatusMessage Status message regarding the app update check.
 * @property isCheckingForUpdate Whether an update check is in progress.
 * @property latestRelease The latest available GitHub release, if any.
 * @property isFlashlightOn Whether the device flashlight is currently on.
 * @property captureStep The current step in the target capture workflow.
 * @property qualityWarning Warning message regarding AR tracking quality.
 * @property captureFailureTimestamp Timestamp of the last capture failure (for UI effects).
 * @property capturedTargetUris List of URIs for captured target images.
 * @property capturedTargetImages List of captured target Bitmaps (in memory).
 * @property evolutionCaptureUris List of URIs representing the evolution of the artwork.
 * @property refinementPaths List of paths used for refining the AR target mask.
 * @property isRefinementEraser Whether the refinement tool is in eraser mode.
 * @property detectedKeypoints List of keypoints detected on the target image.
 * @property targetMaskUri The URI of the generated target mask.
 * @property augmentedImageDatabase The ARCore database containing the trackable images.
 */
@Parcelize
data class UiState(
    val editorMode: EditorMode = EditorMode.HELP,
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
    val backgroundImageUri: Uri? = null,
    val overlayImageUri: Uri? = null,
    val originalOverlayImageUri: Uri? = null,
    val backgroundRemovedImageUri: Uri? = null,
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
    val captureFailureTimestamp: Long = 0L, // Used to trigger UI animations (red glow)
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

    @IgnoredOnParcel
    val augmentedImageDatabase: AugmentedImageDatabase? = null
) : Parcelable