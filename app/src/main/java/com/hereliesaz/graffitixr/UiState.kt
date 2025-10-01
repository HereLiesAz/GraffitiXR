package com.hereliesaz.graffitixr

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.xr.runtime.math.Pose

/**
 * Defines the types of image adjustment sliders that can be displayed in the UI.
 * Each enum corresponds to a specific image property that the user can modify.
 */
enum class SliderType {
    /** Adjusts the transparency of the overlay image. */
    Opacity,

    /** Adjusts the difference between light and dark areas of the overlay image. */
    Contrast,

    /** Adjusts the intensity of the colors in the overlay image. */
    Saturation,

    /** Adjusts the overall lightness or darkness of the overlay image. */
    Brightness,
}

/**
 * Defines the primary operational modes of the application's editor.
 */
enum class EditorMode {
    /**
     * Augmented Reality mode, where the overlay is projected onto detected real-world surfaces.
     * This mode requires an ARCore-compatible device.
     */
    AR,

    /**
     * A simple camera overlay mode for devices that do not support ARCore.
     * The image is overlaid directly on the camera feed without perspective correction.
     */
    NON_AR,

    /**
     * A static image mock-up mode, where the overlay is applied to a static background image
     * selected from the user's gallery.
     */
    STATIC_IMAGE
}

/**
 * Represents the complete, immutable state of the GraffitiXR application's UI at any given time.
 *
 * This data class serves as the single source of truth for the entire UI. It is observed as a
 * [StateFlow] from the [MainViewModel]. Any user interaction or event that should result in a
 * UI change must be processed by the ViewModel to produce a new instance of this state.
 *
 * @property imageUri The [Uri] of the primary overlay image selected by the user.
 * @property backgroundImageUri The [Uri] of the background image used in [EditorMode.STATIC_IMAGE].
 * @property editorMode The currently active [EditorMode] (AR, NON_AR, or STATIC_IMAGE).
 * @property isProcessing A boolean flag indicating if a long-running operation, such as
 *   background removal, is in progress. Used to show loading indicators.
 * @property snackbarMessage A nullable [String]. If not null, the UI should display a snackbar
 *   with this message. It should be consumed (set to null) after being shown.
 * @property activeSlider The currently active [SliderType], or null if no adjustment slider
 *   is currently visible.
 * @property showSettings A boolean flag to control the visibility of the settings screen.
 * @property hue The hue component (0-360) for the dynamic UI theme color.
 * @property lightness The lightness component (0.0-1.0) for the dynamic UI theme color.
 *
 * @property markerPoses A list of [Pose] objects representing the 3D positions of the markers
 *   placed by the user in [EditorMode.AR].
 * @property hitTestPose The current 3D [Pose] of the AR placement cursor, updated continuously
 *   from ARCore's hit-testing results. Null if no surface is detected.
 * @property showARGuidance A boolean flag to control the visibility of the AR plane detection
 *   guidance message.
 *
 * @property stickerCorners A list of four [Offset] points representing the draggable corners
 *   of the overlay image in [EditorMode.STATIC_IMAGE].
 *
 * @property opacity The current opacity level for the overlay image (0.0f to 1.0f).
 * @property contrast The current contrast level for the overlay image.
 * @property saturation The current saturation level for the overlay image.
 * @property brightness The current brightness level for the overlay image.
 */
data class UiState(
    // General App State
    val imageUri: Uri? = null,
    val backgroundImageUri: Uri? = null,
    val editorMode: EditorMode = EditorMode.AR,
    val isProcessing: Boolean = false,
    val snackbarMessage: String? = null,
    val activeSlider: SliderType? = null,
    val showSettings: Boolean = false,
    val hue: Float = 200f,
    val lightness: Float = 0.5f,

    // AR-Specific State
    val markerPoses: List<Pose> = emptyList(),
    val hitTestPose: Pose? = null,
    val showARGuidance: Boolean = false,

    // Static Image Editor State
    val stickerCorners: List<Offset> = emptyList(),

    // Image Adjustment State
    val opacity: Float = 1f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val brightness: Float = 0f,
)