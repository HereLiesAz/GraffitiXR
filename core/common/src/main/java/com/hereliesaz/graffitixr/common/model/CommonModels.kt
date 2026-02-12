package com.hereliesaz.graffitixr.common.model

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset

data class EditorUiState(
    val activeLayerId: String? = null,
    val layers: List<Layer> = emptyList(),
    val editorMode: EditorMode = EditorMode.EDIT,
    val activeRotationAxis: RotationAxis = RotationAxis.Z,
    val isRightHanded: Boolean = true,
    val isImageLocked: Boolean = false,
    val hideUiForCapture: Boolean = false,
    val isLoading: Boolean = false,
    val mapPath: String? = null,
    val backgroundBitmap: Bitmap? = null,
    val activePanel: EditorPanel = EditorPanel.NONE,
    val gestureInProgress: Boolean = false,
    val showRotationAxisFeedback: Boolean = false,
    val showDoubleTapHint: Boolean = false,
    val progressPercentage: Float = 0f
)

enum class EditorMode {
    EDIT, AR, OVERLAY, STATIC, TRACE, CROP, ADJUST, DRAW, PROJECT, ISOLATE, BALANCE, OUTLINE
}

enum class EditorPanel {
    NONE, LAYERS, ADJUST, COLOR, BLEND
}

enum class RotationAxis { X, Y, Z }

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
    val blendMode: BlendMode = BlendMode.SrcOver
)

enum class BlendMode {
    SrcOver, Multiply, Screen, Overlay, Darken, Lighten, ColorDodge, ColorBurn,
    HardLight, SoftLight, Difference, Exclusion, Hue, Saturation, Color, Luminosity,
    Clear, Src, Dst, DstOver, SrcIn, DstIn, SrcOut, DstOut, SrcAtop, DstAtop,
    Xor, Plus, Modulate
}
