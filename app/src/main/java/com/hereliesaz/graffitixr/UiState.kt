package com.hereliesaz.graffitixr

import android.net.Uri
import android.os.Parcelable
import androidx.compose.ui.geometry.Offset
import com.google.ar.core.Pose
import com.hereliesaz.graffitixr.graphics.ArFeaturePattern
import com.hereliesaz.graffitixr.utils.OffsetParceler
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue
import kotlinx.parcelize.TypeParceler

/**
 * Represents the different editing modes available in the application.
 * Each mode provides a distinct user experience for visualizing the mural.
 */
enum class EditorMode {
    STATIC,
    NON_AR,
    AR
}

/**
 * Represents the complete and immutable state of the user interface at any given time.
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
    val arErrorMessage: String? = null,
    val arImagePose: Pose? = null,
    val arFeaturePattern: ArFeaturePattern? = null,
    val completedOnboardingModes: @RawValue Set<EditorMode> = emptySet()
) : Parcelable