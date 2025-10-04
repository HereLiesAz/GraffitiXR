package com.hereliesaz.graffitixr

import android.net.Uri
import android.os.Parcelable
import androidx.compose.ui.geometry.Offset
import com.google.ar.core.Pose
import com.hereliesaz.graffitixr.graphics.ArFeaturePattern
import com.hereliesaz.graffitixr.utils.OffsetParceler
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue
import kotlinx.parcelize.TypeParceler

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

    /**
     * A mode that uses Augmented Reality to project the mural onto a surface in the real world.
     * This provides the most realistic and immersive preview.
     */
    AR
}

/**
 * Represents the complete and immutable state of the user interface at any given time.
 *
 * This data class acts as the single source of truth for the UI. It is observed by the
 * composables, and any change to an instance of this class will trigger a recomposition
 * to reflect the new state. All properties have default values to ensure a consistent
 * initial state.
 */
@Parcelize
@TypeParceler<Offset, OffsetParceler>
data class UiState(
    val editorMode: EditorMode = EditorMode.STATIC,
    val backgroundImageUri: Uri? = null,
    val overlayImageUri: Uri? = null,
    val backgroundRemovedImageUri: Uri? = null,
    val opacity: Float = 1f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val scale: Float = 1f,
    val offset: Offset = Offset.Zero,
    val points: List<Offset> = emptyList(),
    val isArLocked: Boolean = false,
    val isLoading: Boolean = false,
    val isArSupported: Boolean = true,
    val completedOnboardingModes: @RawValue Set<EditorMode> = emptySet()
) : Parcelable {
    @IgnoredOnParcel
    val arImagePose: Pose? = null
    @IgnoredOnParcel
    val arFeaturePattern: ArFeaturePattern? = null
}