package com.hereliesaz.graffitixr

import android.net.Uri
import androidx.xr.runtime.math.Pose

import androidx.compose.ui.geometry.Offset

/**
 * Represents the different types of sliders available for image adjustment.
 */
enum class SliderType {
    Opacity,
    Contrast,
    Saturation,
}

/**
 * Represents the different modes of the editor.
 */
enum class EditorMode {
    AR,
    NON_AR,
    STATIC_IMAGE
}

/**
 * Represents the overall state of the UI for the GraffitiXR application.
 *
 * @property imageUri The URI of the image selected by the user (the overlay).
 * @property backgroundImageUri The URI of the background image for static mode.
 * @property editorMode The current editor mode.
 * @property isProcessing Whether a long-running operation (like background removal) is in progress.
 * @property snackbarMessage A message to be shown in a snackbar. Null if no message should be shown.
 * @property activeSlider The currently active slider type, or null if no slider is active.
 * @property showSettings Whether the settings screen is visible.
 * @property hue The hue value for UI color customization.
 * @property lightness The lightness value for UI color customization.
 *
 * @property markerPoses A list of poses for each placed marker in AR mode.
 * @property hitTestPose The current pose of the placement cursor in AR mode.
 *
 * @property stickerCorners The positions of the four corners of the overlay in static image mode.
 *
 * @property opacity The opacity level of the image.
 * @property contrast The contrast level of the image.
 * @property saturation The saturation level of the image.
 */
data class UiState(
    // General State
    val imageUri: Uri? = null,
    val backgroundImageUri: Uri? = null,
    val editorMode: EditorMode = EditorMode.AR,
    val isProcessing: Boolean = false,
    val snackbarMessage: String? = null,
    val activeSlider: SliderType? = null,
    val showSettings: Boolean = false,
    val hue: Float = 200f,
    val lightness: Float = 0.5f,

    // AR State
    val markerPoses: List<Pose> = emptyList(),
    val hitTestPose: Pose? = null,

    // Static Image State
    val stickerCorners: List<Offset> = emptyList(),

    // Slider values
    val opacity: Float = 1f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
)