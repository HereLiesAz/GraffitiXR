// ~~~ FILE: ./core/common/src/main/java/com/hereliesaz/graffitixr/common/model/UiState.kt ~~~
package com.hereliesaz.graffitixr.common.model

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

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
    val capturedTargetImages: List<Bitmap> = emptyList(),
    val gpsData: GpsData? = null,
    val sensorData: SensorData? = null,
    val pendingKeyframePath: String? = null,
    val unwarpPoints: List<Offset> = emptyList(),
    val activeUnwarpPointIndex: Int = -1,
    val magnifierPosition: Offset = Offset.Zero,
    val maskPath: androidx.compose.ui.graphics.Path? = null,
    val isCaptureRequested: Boolean = false
)

enum class Tool {
    NONE, BRUSH, ERASER, BLUR, HEAL, BURN, DODGE, LIQUIFY
}

data class EditorUiState(
    val activeLayerId: String? = null,
    val layers: List<Layer> = emptyList(),
    val editorMode: EditorMode = EditorMode.TRACE,
    val activeRotationAxis: RotationAxis = RotationAxis.Z,
    val isRightHanded: Boolean = true,
    val isImageLocked: Boolean = false,
    val hideUiForCapture: Boolean = false,
    val isLoading: Boolean = false,
    val mapPath: String? = null,
    val backgroundBitmap: Bitmap? = null,
    val backgroundImageUri: String? = null,
    val isEditingBackground: Boolean = false,
    val backgroundScale: Float = 1f,
    val backgroundOffset: Offset = Offset.Zero,
    val activePanel: EditorPanel = EditorPanel.NONE,
    val gestureInProgress: Boolean = false,
    val showRotationAxisFeedback: Boolean = false,
    val showDoubleTapHint: Boolean = false,
    val progressPercentage: Float = 0f,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val editingLayerId: String? = null,
    val editingLayerName: String = "",

    // Sketching Tools State
    val activeTool: Tool = Tool.NONE,
    val activeColor: Color = Color.White,
    val brushSize: Float = 20f,
    val colorHistory: List<Color> = emptyList(),
    val showColorPicker: Boolean = false,
    val showSizePicker: Boolean = false
)

enum class CaptureStep {
    NONE, CAPTURE, RECTIFY, MASK, REVIEW
}

enum class EditorPanel {
    NONE, LAYERS, ADJUST, COLOR, BLEND
}

data class Layer(
    val id: String,
    val name: String,
    val bitmap: Bitmap,
    val uri: Uri,
    val offset: Offset = Offset.Zero,
    val scale: Float = 1f,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,
    val isVisible: Boolean = true,
    val isImageLocked: Boolean = false,
    val opacity: Float = 1f,
    val blendMode: BlendMode = BlendMode.SrcOver,
    val saturation: Float = 1f,
    val contrast: Float = 1f,
    val brightness: Float = 0f,
    val colorBalanceR: Float = 0f,
    val colorBalanceG: Float = 0f,
    val colorBalanceB: Float = 0f,
    val warpMesh: List<Float>? = null,
    val isSketch: Boolean = false
)

enum class BlendMode {
    SrcOver, Multiply, Screen, Overlay, Darken, Lighten, ColorDodge, ColorBurn,
    HardLight, SoftLight, Difference, Exclusion, Hue, Saturation, Color, Luminosity,
    Clear, Src, Dst, DstOver, SrcIn, DstIn, SrcOut, DstOut, SrcAtop, DstAtop,
    Xor, Plus, Modulate
}