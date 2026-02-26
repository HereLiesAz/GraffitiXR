package com.hereliesaz.graffitixr.common.model

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode

/**
 * Represents a single graphical layer in the editor with all aesthetic and spatial parameters.
 */
data class Layer(
    val id: String,
    val name: String,
    val uri: Uri? = null,
    val bitmap: Bitmap? = null,
    val isVisible: Boolean = true,
    val opacity: Float = 1.0f,
    val brightness: Float = 0.0f,
    val contrast: Float = 1.0f,
    val saturation: Float = 1.0f,
    val colorBalanceR: Float = 0.0f,
    val colorBalanceG: Float = 0.0f,
    val colorBalanceB: Float = 0.0f,
    val isImageLocked: Boolean = false,
    val isSketch: Boolean = false,
    val blendMode: BlendMode = BlendMode.SrcOver,
    val warpMesh: List<Float> = emptyList(),
    val offset: Offset = Offset.Zero,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,
    val scale: Float = 1.0f
)

/**
 * Available operational modes for the GraffitiXR environment.
 */
enum class EditorMode {
    TRACE,
    MOCKUP,
    OVERLAY,
    AR
}

/**
 * UI panels available for interaction within the editor interface.
 */
enum class EditorPanel {
    NONE,
    LAYERS,
    ADJUSTMENTS,
    TRANSFORM,
    COLOR,
    ADJUST
}

/**
 * Transformation axes for 3D manipulation.
 */
enum class RotationAxis {
    X,
    Y,
    Z
}

/**
 * Tools available for sketch and modification.
 */


/**
 * The global state for the Editor UI, including AR and Gesture feedback flags.
 */
data class EditorUiState(
    val projectId: String? = null,
    val layers: List<Layer> = emptyList(),
    val backgroundBitmap: Bitmap? = null,
    val activeLayerId: String? = null,
    val activePanel: EditorPanel = EditorPanel.NONE,
    val editorMode: EditorMode = EditorMode.AR,
    val activeTool: Tool = Tool.BRUSH,
    val hideUiForCapture: Boolean = false,
    val isRightHanded: Boolean = true,
    val gestureInProgress: Boolean = false,
    val showRotationAxisFeedback: Boolean = false,
    val activeRotationAxis: RotationAxis = RotationAxis.Z,
    val undoCount: Int = 0,
    val redoCount: Int = 0
)