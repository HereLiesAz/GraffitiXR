package com.hereliesaz.graffitixr

import android.net.Uri
import android.os.Parcelable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import com.hereliesaz.graffitixr.utils.OffsetParceler
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue
import kotlinx.parcelize.WriteWith

/**
 * Represents the different states of the Image Target creation process.
 */
@Parcelize
sealed class TargetCreationState : Parcelable {
    /**
     * The initial state where no target creation is in progress.
     */
    @Parcelize
    data object IDLE : TargetCreationState()

    /**
     * The state where the application is actively creating an Image Target.
     */
    @Parcelize
    data object CREATING : TargetCreationState()

    /**
     * The state after an Image Target has been successfully created.
     */
    @Parcelize
    data object SUCCESS : TargetCreationState()

    /**
     * The state when an error has occurred during the target creation process.
     */
    @Parcelize
    data object ERROR : TargetCreationState()
}


/**
 * Represents the complete and immutable state of the user interface at any given time.
 *
 * This data class acts as the single source of truth for the UI. It is observed by the
 * composables, and any change to an instance of this class will trigger a recomposition
 * to reflect the new state. All properties have default values to ensure a consistent
 * initial state.
 *
 * This class is [Parcelable] to allow it to be saved and restored from a [SavedStateHandle],
 * ensuring the UI state survives process death.
 *
 * @property editorMode The currently active editor mode, which determines the main screen content. See [EditorMode].
 * @property backgroundImageUri The [Uri] of the image selected by the user to serve as the background. This is only used in [EditorMode.STATIC].
 * @property overlayImageUri The [Uri] of the mural or artwork image selected by the user to be overlaid on the background or camera feed.
 * @property opacity The transparency of the overlay image, ranging from 0.0f (fully transparent) to 1.0f (fully opaque).
 * @property contrast The contrast of the overlay image. A value of 1.0f is normal contrast.
 * @property saturation The color saturation of the overlay image. A value of 0.0f is grayscale, and 1.0f is normal saturation.
 * @property scale The uniform scale factor applied to the overlay image in non-AR mode.
 * @property offset The offset of the overlay image in non-AR mode.
 * @property points A list of four [Offset] points representing the corners of the overlay image for perspective warping in static mode.
 * @property arImagePose The pose of the AR image in the real world.
 * @property arFeaturePattern The feature pattern detected in the AR scene.
 * @property isArLocked A flag indicating whether the AR image is locked in place.
 * @property completedOnboardingModes A set containing the [EditorMode]s for which the user has already seen and dismissed the onboarding dialog. This is used to prevent showing the dialog repeatedly.
 */
@Parcelize
data class UiState(
    val editorMode: EditorMode = EditorMode.HELP,
    val backgroundImageUri: Uri? = null,
    val overlayImageUri: Uri? = null,
    val backgroundRemovedImageUri: Uri? = null,
    val opacity: Float = 0.5f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val colorBalanceR: Float = 1f,
    val colorBalanceG: Float = 1f,
    val colorBalanceB: Float = 1f,
    val scale: Float = 1f,
    val rotationZ: Float = 0f,
    val offset: @WriteWith<OffsetParceler> Offset = Offset.Zero,
    val arObjectScale: Float = 1f,
    val isLoading: Boolean = false,
    val completedOnboardingModes: Set<EditorMode> = emptySet(),
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val activeRotationAxis: RotationAxis = RotationAxis.Z,
    val showRotationAxisFeedback: Boolean = false,
    val showDoubleTapHint: Boolean = false,
    val arState: ArState = ArState.SEARCHING,
    val targetCreationState: TargetCreationState = TargetCreationState.IDLE,
    val blendMode: @WriteWith<com.hereliesaz.graffitixr.utils.BlendModeParceler> BlendMode = BlendMode.SrcOver,
    val showCurvesDialog: Boolean = false,
    val fingerprintJson: String? = null,
    val curvesPoints: @RawValue List<Offset> = listOf(Offset(0f, 0f), Offset(1f, 1f)),
    val processedImageUri: Uri? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isMarkingProgress: Boolean = false,
    val drawingPaths: @RawValue List<List<Pair<Float, Float>>> = emptyList(),
    val progressPercentage: Float = 0f,
    val refinementImageUri: Uri? = null,
    val showOnboardingDialogForMode: EditorMode? = null
) : Parcelable

enum class AppBlendMode {
    NORMAL,
    MULTIPLY,
    SCREEN,
    OVERLAY,
    DARKEN,
    LIGHTEN;

    fun toComposeBlendMode(): BlendMode {
        return when (this) {
            NORMAL -> BlendMode.SrcOver
            MULTIPLY -> BlendMode.Multiply
            SCREEN -> BlendMode.Screen
            OVERLAY -> BlendMode.Overlay
            DARKEN -> BlendMode.Darken
            LIGHTEN -> BlendMode.Lighten
        }
    }
}
