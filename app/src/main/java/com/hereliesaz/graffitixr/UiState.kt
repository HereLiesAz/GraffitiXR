package com.hereliesaz.graffitixr

import android.net.Uri
import androidx.xr.runtime.math.Pose

/**
 * Represents the different types of sliders available for image adjustment.
 */
enum class SliderType {
    Opacity,
    Contrast,
    Saturation,
}

/**
 * Represents the overall state of the UI for the GraffitiXR application.
 *
 * @property imageUri The URI of the image selected by the user.
 * @property markerPoses A list of poses for each placed marker.
 * @property hitTestPose The current pose of the placement cursor, based on a hit test.
 * @property isProcessing Whether a long-running operation (like background removal) is in progress.
 * @property snackbarMessage A message to be shown in a snackbar. Null if no message should be shown.
 * @property opacity The opacity level of the image.
 * @property contrast The contrast level of the image.
 * @property saturation The saturation level of the image.
 * @property activeSlider The currently active slider type, or null if no slider is active.
 * @property showSettings Whether the settings screen is visible.
 * @property hue The hue value for UI color customization.
 * @property lightness The lightness value for UI color customization.
 */
data class UiState(
    val imageUri: Uri? = null,
    val isProcessing: Boolean = false,
    val snackbarMessage: String? = null,

    // AR State
    val markerPoses: List<Pose> = emptyList(),
    val hitTestPose: Pose? = null,

    // Slider values
    val opacity: Float = 1f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,

    val activeSlider: SliderType? = null,

    // Settings
    val showSettings: Boolean = false,
    val hue: Float = 200f,
    val lightness: Float = 0.5f
)