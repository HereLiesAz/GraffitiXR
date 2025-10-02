package com.hereliesaz.graffitixr

/**
 * Represents the immutable state of the UI.
 * This data class will hold all the necessary information
 * to render the user interface at any given time.
 */
import android.net.Uri
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.xr.core.Pose

/**
 * Enum to represent the different editor modes.
 */
enum class EditorMode {
    STATIC, // Mock-up on a static image
    NON_AR, // On-the-go camera overlay
    AR      // Augmented Reality mode
}

/**
 * Represents the immutable state of the UI.
 * This data class will hold all the necessary information
 * to render the user interface at any given time.
 *
 * @param editorMode The current editor mode.
 * @param backgroundImageUri The URI of the selected background image.
 * @param overlayImageUri The URI of the selected overlay image.
 * @param opacity The opacity of the overlay image.
 * @param contrast The contrast of the overlay image.
 * @param saturation The saturation of the overlay image.
 * @param scale The scale of the overlay image.
 * @param rotation The rotation of the overlay image.
 * @param points The four corner points for the perspective warp.
 * @param arMarkers The list of 3D markers placed in the AR scene.
 * @param completedOnboardingModes A set of modes for which the user has completed the onboarding.
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
    val arMarkers: List<Pose> = emptyList(),
    val completedOnboardingModes: Set<EditorMode> = emptySet()
)