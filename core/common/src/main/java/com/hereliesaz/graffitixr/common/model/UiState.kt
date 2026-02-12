package com.hereliesaz.graffitixr.common.model

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset

// Main AR State
data class ArUiState(
    val isScanning: Boolean = false,
    val pointCloudCount: Int = 0,
    val planeCount: Int = 0,
    val isTargetDetected: Boolean = false,
    val trackingState: String = "Initializing",
    val showPointCloud: Boolean = true,
    val isFlashlightOn: Boolean = false,
    val tempCaptureBitmap: Bitmap? = null,
    val capturedTargetUris: List<Uri> = emptyList(),
    val capturedTargetImages: List<Bitmap> = emptyList()
)

// Editor State
data class EditorUiState(
    val activeLayerId: String? = null,
    val layers: List<Layer> = emptyList(),
    val editorMode: EditorMode = EditorMode.EDIT,
    val activeRotationAxis: RotationAxis = RotationAxis.Z,
    val isRightHanded: Boolean = true,
    val isImageLocked: Boolean = false,
    val hideUiForCapture: Boolean = false,
    val isLoading: Boolean = false,

    // Background Fields
    val mapPath: String? = null,
    val backgroundBitmap: Bitmap? = null,
    val backgroundImageUri: String? = null,

    // Background Editing
    val isEditingBackground: Boolean = false,
    val backgroundScale: Float = 1f,
    val backgroundOffset: Offset = Offset.Zero,

    // Tool State
    val activePanel: EditorPanel = EditorPanel.NONE,
    val gestureInProgress: Boolean = false,
    val showRotationAxisFeedback: Boolean = false,
    val showDoubleTapHint: Boolean = false,
    val progressPercentage: Float = 0f
)

// Main App Flow State
enum class CaptureStep {
    NONE, CAPTURE, RECTIFY, REVIEW
}

// Helper Classes
enum class EditorPanel {
    NONE, LAYERS, ADJUST, COLOR, BLEND
}

data class Layer(
    val id: String,
    val name: String,
    val bitmap: Bitmap,
    val offset: Offset = Offset.Zero,
    val scale: Float = 1f,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,
    val isVisible: Boolean = true,
    val opacity: Float = 1f,
    val blendMode: BlendMode = BlendMode.SrcOver,

    // Image Adjustment
    val saturation: Float = 1f,
    val contrast: Float = 1f,
    val brightness: Float = 0f,
    val colorBalanceR: Float = 0f,
    val colorBalanceG: Float = 0f,
    val colorBalanceB: Float = 0f
)

enum class BlendMode {
    SrcOver, Multiply, Screen, Overlay, Darken, Lighten, ColorDodge, ColorBurn,
    HardLight, SoftLight, Difference, Exclusion, Hue, Saturation, Color, Luminosity,
    Clear, Src, Dst, DstOver, SrcIn, DstIn, SrcOut, DstOut, SrcAtop, DstAtop,
    Xor, Plus, Modulate
}