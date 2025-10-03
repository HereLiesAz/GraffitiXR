package com.hereliesaz.graffitixr

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.graphics.ArFeaturePattern

/**
 * Defines the different operating modes of the application.
 */
enum class EditorMode {
    IMAGE_TRACE,
    MOCK_UP,
    AR_OVERLAY
}

/**
 * Represents the complete and immutable state of the user interface at any given time.
 *
 * @property editorMode The currently active editor mode.
 * @property overlayImageUri The [Uri] of the artwork image selected by the user.
 * @property backgroundImageUri The [Uri] of the background image for mock-up mode.
 * @property opacity The transparency of the overlay image.
 * @property contrast The contrast of the overlay image.
 * @property saturation The color saturation of the overlay image.
 * @property imageTraceScale The scale factor for the overlay in Image Trace mode.
 * @property imageTraceOffset The offset for the overlay in Image Trace mode.
 * @property mockupPoints The list of four [Offset] points for the perspective warp in mock-up mode.
 * @property arImagePose The pose of the manually placed image in AR mode.
 * @property arFeaturePattern The unique "fingerprint" of the locked AR scene.
 * @property isArLocked A flag indicating whether the AR projection is locked.
 * @property isLoading A flag to indicate that a background process is running.
 * @property isWarpEnabled A flag indicating if the warp handles should be active.
 * @property mockupPointsHistory A list of previous states of `mockupPoints`.
 * @property mockupPointsHistoryIndex The current position in the `mockupPointsHistory`.
 */
data class UiState(
    val editorMode: EditorMode = EditorMode.IMAGE_TRACE,
    val overlayImageUri: Uri? = null,
    val backgroundImageUri: Uri? = null,
    val opacity: Float = 1.0f,
    val contrast: Float = 1.0f,
    val saturation: Float = 1.0f,
    // Image Trace State
    val imageTraceScale: Float = 1f,
    val imageTraceOffset: Offset = Offset.Zero,
    // Mock-up State
    val mockupPoints: List<Offset> = emptyList(),
    val isWarpEnabled: Boolean = false,
    val mockupPointsHistory: List<List<Offset>> = emptyList(),
    val mockupPointsHistoryIndex: Int = -1,
    // AR State
    val arImagePose: FloatArray? = null,
    val arFeaturePattern: ArFeaturePattern? = null,
    val isArLocked: Boolean = false,
    // Global State
    val isLoading: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UiState

        if (editorMode != other.editorMode) return false
        if (overlayImageUri != other.overlayImageUri) return false
        if (backgroundImageUri != other.backgroundImageUri) return false
        if (opacity != other.opacity) return false
        if (contrast != other.contrast) return false
        if (saturation != other.saturation) return false
        if (imageTraceScale != other.imageTraceScale) return false
        if (imageTraceOffset != other.imageTraceOffset) return false
        if (mockupPoints != other.mockupPoints) return false
        if (isWarpEnabled != other.isWarpEnabled) return false
        if (mockupPointsHistory != other.mockupPointsHistory) return false
        if (mockupPointsHistoryIndex != other.mockupPointsHistoryIndex) return false
        if (arImagePose != null) {
            if (other.arImagePose == null) return false
            if (!arImagePose.contentEquals(other.arImagePose)) return false
        } else if (other.arImagePose != null) return false
        if (arFeaturePattern != other.arFeaturePattern) return false
        if (isArLocked != other.isArLocked) return false
        if (isLoading != other.isLoading) return false

        return true
    }

    override fun hashCode(): Int {
        var result = editorMode.hashCode()
        result = 31 * result + (overlayImageUri?.hashCode() ?: 0)
        result = 31 * result + (backgroundImageUri?.hashCode() ?: 0)
        result = 31 * result + opacity.hashCode()
        result = 31 * result + contrast.hashCode()
        result = 31 * result + saturation.hashCode()
        result = 31 * result + imageTraceScale.hashCode()
        result = 31 * result + imageTraceOffset.hashCode()
        result = 31 * result + mockupPoints.hashCode()
        result = 31 * result + isWarpEnabled.hashCode()
        result = 31 * result + mockupPointsHistory.hashCode()
        result = 31 * result + mockupPointsHistoryIndex
        result = 31 * result + (arImagePose?.contentHashCode() ?: 0)
        result = 31 * result + (arFeaturePattern?.hashCode() ?: 0)
        result = 31 * result + isArLocked.hashCode()
        result = 31 * result + isLoading.hashCode()
        return result
    }
}