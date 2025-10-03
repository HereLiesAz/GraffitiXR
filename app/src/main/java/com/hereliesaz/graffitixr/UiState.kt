package com.hereliesaz.graffitixr

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.graphics.ArFeaturePattern

enum class EditorMode {
    STATIC,
    NON_AR,
    AR_OVERLAY,
    IMAGE_TRACE,
    MOCK_UP
}

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
    val completedOnboardingModes: Set<EditorMode> = emptySet(),
    val mockupPoints: List<Offset> = emptyList(),
    val mockupPointsHistory: List<List<Offset>> = emptyList(),
    val mockupPointsHistoryIndex: Int = -1,
    val arImagePose: FloatArray? = null,
    val arFeaturePattern: ArFeaturePattern? = null,
    val isArLocked: Boolean = false,
    val imageTraceScale: Float = 1f,
    val imageTraceOffset: Offset = Offset.Zero,
    val isWarpEnabled: Boolean = false,
    val isLoading: Boolean = false,
    val colorMatrix: FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UiState

        if (editorMode != other.editorMode) return false
        if (backgroundImageUri != other.backgroundImageUri) return false
        if (overlayImageUri != other.overlayImageUri) return false
        if (opacity != other.opacity) return false
        if (contrast != other.contrast) return false
        if (saturation != other.saturation) return false
        if (scale != other.scale) return false
        if (rotation != other.rotation) return false
        if (points != other.points) return false
        if (completedOnboardingModes != other.completedOnboardingModes) return false
        if (mockupPoints != other.mockupPoints) return false
        if (mockupPointsHistory != other.mockupPointsHistory) return false
        if (mockupPointsHistoryIndex != other.mockupPointsHistoryIndex) return false
        if (arImagePose != null) {
            if (other.arImagePose == null) return false
            if (!arImagePose.contentEquals(other.arImagePose)) return false
        } else if (other.arImagePose != null) return false
        if (arFeaturePattern != other.arFeaturePattern) return false
        if (isArLocked != other.isArLocked) return false
        if (imageTraceScale != other.imageTraceScale) return false
        if (imageTraceOffset != other.imageTraceOffset) return false
        if (isWarpEnabled != other.isWarpEnabled) return false
        if (isLoading != other.isLoading) return false
        if (!colorMatrix.contentEquals(other.colorMatrix)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = editorMode.hashCode()
        result = 31 * result + (backgroundImageUri?.hashCode() ?: 0)
        result = 31 * result + (overlayImageUri?.hashCode() ?: 0)
        result = 31 * result + opacity.hashCode()
        result = 31 * result + contrast.hashCode()
        result = 31 * result + saturation.hashCode()
        result = 31 * result + scale.hashCode()
        result = 31 * result + rotation.hashCode()
        result = 31 * result + points.hashCode()
        result = 31 * result + completedOnboardingModes.hashCode()
        result = 31 * result + mockupPoints.hashCode()
        result = 31 * result + mockupPointsHistory.hashCode()
        result = 31 * result + mockupPointsHistoryIndex
        result = 31 * result + (arImagePose?.contentHashCode() ?: 0)
        result = 31 * result + (arFeaturePattern?.hashCode() ?: 0)
        result = 31 * result + isArLocked.hashCode()
        result = 31 * result + imageTraceScale.hashCode()
        result = 31 * result + imageTraceOffset.hashCode()
        result = 31 * result + isWarpEnabled.hashCode()
        result = 31 * result + isLoading.hashCode()
        result = 31 * result + colorMatrix.contentHashCode()
        return result
    }
}