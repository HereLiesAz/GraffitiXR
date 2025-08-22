package com.hereliesaz.graffitixr

import android.net.Uri
import com.google.ar.core.Pose

enum class SliderType {
    Opacity,
    Contrast,
    Saturation,
    Brightness
}

data class UiState(
    val imageUri: Uri? = null,
    val placementMode: Boolean = true,
    val lockedPose: Pose? = null,
    val cameraPose: Pose? = null,
    val isProcessing: Boolean = false,
    val snackbarMessage: String? = null,

    // Slider values
    val opacity: Float = 1f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val brightness: Float = 0f,

    val activeSlider: SliderType? = null
)
