package com.hereliesaz.graffitixr

import android.net.Uri
import android.os.Parcelable
import androidx.compose.ui.geometry.Offset
import com.google.ar.core.Pose
import com.hereliesaz.graffitixr.graphics.ArFeaturePattern
import com.hereliesaz.graffitixr.utils.ArFeaturePatternParceler
import com.hereliesaz.graffitixr.utils.OffsetParceler
import com.hereliesaz.graffitixr.utils.PoseParceler
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith

/**
 * Represents the different editing modes available in the application.
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
data class UiState(
    val editorMode: EditorMode = EditorMode.STATIC,
    val backgroundImageUri: Uri? = null,
    val overlayImageUri: Uri? = null,
    val backgroundRemovedImageUri: Uri? = null,
    val opacity: Float = 1f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val scale: Float = 1f,
    val rotationZ: Float = 0f,
    val offset: @WriteWith<OffsetParceler> Offset = Offset.Zero,
    val arImagePose: @WriteWith<PoseParceler> Pose? = null,
    val arFeaturePattern: @WriteWith<ArFeaturePatternParceler> ArFeaturePattern? = null,
    val arState: ArState = ArState.SEARCHING,
    val isLoading: Boolean = false,
    val completedOnboardingModes: Set<EditorMode> = emptySet(),
    val arePlanesDetected: Boolean = false,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val activeRotationAxis: RotationAxis = RotationAxis.Z,
    val showRotationAxisFeedback: Boolean = false,
    val arDrawingProgress: Float = 0f
) : Parcelable