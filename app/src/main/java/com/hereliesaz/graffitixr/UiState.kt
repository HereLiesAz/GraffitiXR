package com.hereliesaz.graffitixr

import android.net.Uri
import androidx.xr.arcore.Anchor

/**
 * Represents the different types of sliders available for image adjustment.
 */
enum class SliderType {
    Opacity,
    Contrast,
    Saturation,
    Brightness
}

/**
 * Represents the overall state of the UI for the GraffitiXR application.
 *
 * @property imageUri The URI of the image selected by the user.
 * @property placementMode Whether the app is in placement mode (true) or the mural is locked (false).
 * @property graffiti an array of anchors for each graffiti placed in the scene
 * @property isProcessing Whether a long-running operation (like background removal) is in progress.
 * @property snackbarMessage A message to be shown in a snackbar. Null if no message should be shown.
 * @property opacity The opacity level of the image.
 * @property contrast The contrast level of the image.
 * @property saturation The saturation level of the image.
 * @property brightness The brightness level of the image.
 * @property activeSlider The currently active slider type, or null if no slider is active.
 * @property showSettings Whether the settings screen is visible.
 * @property hue The hue value for UI color customization.
 * @property lightness The lightness value for UI color customization.
 */
data class UiState(
    val imageUri: Uri? = null,
    val placementMode: Boolean = true,
    val graffiti: List<Anchor> = emptyList(),
    val isProcessing: Boolean = false,
    val snackbarMessage: String? = null,

    // Slider values
    val opacity: Float = 1f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val brightness: Float = 0f,

    val activeSlider: SliderType? = null,

    // Settings
    val showSettings: Boolean = false,
    val hue: Float = 200f,
    val lightness: Float = 0.5f
)