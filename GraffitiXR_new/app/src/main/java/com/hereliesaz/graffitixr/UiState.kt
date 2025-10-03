package com.hereliesaz.graffitixr

import android.net.Uri
import androidx.compose.ui.geometry.Offset

/**
 * Represents the different editing modes available in the application.
 * Each mode provides a distinct user experience for visualizing the mural.
 */
enum class EditorMode {
    /**
     * A mode for mocking up a mural on a static background image.
     * This allows for precise placement and adjustments in a controlled environment.
     */
    STATIC,

    /**
     * A mode for overlaying a mural on a live camera feed without using
     * Augmented Reality tracking. This is a lightweight option for quick, on-the-go previews.
     */
    NON_AR,
}

/**
 * Represents the complete and immutable state of the user interface at any given time.
 *
 * This data class acts as the single source of truth for the UI. It is observed by the
 * composables, and any change to an instance of this class will trigger a recomposition
 * to reflect the new state. All properties have default values to ensure a consistent
 * initial state.
 *
 * @property editorMode The currently active editor mode, which determines the main screen content. See [EditorMode].
 * @property backgroundImageUri The [Uri] of the image selected by the user to serve as the background. This is only used in [EditorMode.STATIC].
 * @property overlayImageUri The [Uri] of the mural or artwork image selected by the user to be overlaid on the background or camera feed.
 * @property opacity The transparency of the overlay image, ranging from 0.0f (fully transparent) to 1.0f (fully opaque).
 * @property contrast The contrast of the overlay image. A value of 1.0f is normal contrast.
 * @property saturation The color saturation of the overlay image. A value of 0.0f is grayscale, and 1.0f is normal saturation.
 * @property scale The uniform scale factor applied to the overlay image. Used for pinch-to-zoom gestures.
 * @property rotation The rotation angle of the overlay image in degrees. Used for twist gestures.
 * @property points A list of four [Offset] points representing the corners of the overlay image for perspective warping in non-AR modes.
 * @property completedOnboardingModes A set containing the [EditorMode]s for which the user has already seen and dismissed the onboarding dialog. This is used to prevent showing the dialog repeatedly.
 */
data class UiState(
    val editorMode: EditorMode = EditorMode.STATIC,
    val backgroundImageUri: Uri? = null,
    val overlayImageUri: Uri? = null,
    val opacity: Float = 1f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val points: List<Offset> = emptyList(),
    val completedOnboardingModes: Set<EditorMode> = emptySet()
)