// FILE: core/common/src/main/java/com/hereliesaz/graffitixr/common/model/EditorModels.kt
package com.hereliesaz.graffitixr.common.model

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import com.hereliesaz.graffitixr.common.serialization.BlendModeSerializer
import com.hereliesaz.graffitixr.common.serialization.OffsetSerializer
import com.hereliesaz.graffitixr.common.serialization.UriSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Represents a single graphical layer in the editor with all aesthetic and spatial parameters.
 * Serializable fields cover all wire-transferable state. Bitmap is runtime-only and excluded.
 */
@Serializable
data class Layer(
    val id: String,
    val name: String,
    @Serializable(with = UriSerializer::class)
    val uri: Uri? = null,
    @Transient
    val bitmap: Bitmap? = null,
    val isVisible: Boolean = true,
    val opacity: Float = 1.0f,
    val brightness: Float = 0.0f,
    val contrast: Float = 1.0f,
    val saturation: Float = 1.0f,
    val colorBalanceR: Float = 1.0f,
    val colorBalanceG: Float = 1.0f,
    val colorBalanceB: Float = 1.0f,
    val isImageLocked: Boolean = false,
    val isSketch: Boolean = false,
    val textParams: TextLayerParams? = null,
    val isLinked: Boolean = false,
    @Serializable(with = BlendModeSerializer::class)
    val blendMode: BlendMode = BlendMode.SrcOver,
    val warpMesh: List<Float> = emptyList(),
    @Serializable(with = OffsetSerializer::class)
    val offset: Offset = Offset.Zero,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,
    val scale: Float = 1.0f,
    val isInverted: Boolean = false,
    val stencilType: StencilLayerType? = null,
    val stencilSourceId: String? = null
)

/**
 * The subset of a layer's visual/aesthetic properties that can be changed without replacing
 * the whole layer. Used by Op.LayerPropsChange to stream property-only mutations over the wire.
 */
@Serializable
data class LayerProps(
    val isVisible: Boolean = true,
    val opacity: Float = 1.0f,
    val brightness: Float = 0.0f,
    val contrast: Float = 1.0f,
    val saturation: Float = 1.0f,
    val colorBalanceR: Float = 1.0f,
    val colorBalanceG: Float = 1.0f,
    val colorBalanceB: Float = 1.0f,
    val isImageLocked: Boolean = false,
    val isInverted: Boolean = false,
    @Serializable(with = BlendModeSerializer::class)
    val blendMode: BlendMode = BlendMode.SrcOver
)

/**
 * A completed brush stroke, coarse-grained: only emitted once the user lifts their finger.
 * Points are encoded as interleaved [x0, y0, x1, y1, ...] for compact wire transfer.
 */
@Serializable
data class BrushStroke(
    val points: List<Float> = emptyList(),
    val colorArgb: Long = 0xFFFFFFFF,
    val brushSize: Float = 50f,
    val brushFeathering: Float = 0f,
    val blendModeOrdinal: Int = 3 // BlendMode.SrcOver ordinal
)

/**
 * Available operational modes for the GraffitiXR environment.
 */
enum class EditorMode {
    TRACE,
    MOCKUP,
    OVERLAY,
    AR,
    STENCIL
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
 * The global state for the Editor UI, including AR and Gesture feedback flags.
 */
data class EditorUiState(
    val projectId: String? = null,
    val layers: List<Layer> = emptyList(),
    val backgroundBitmap: Bitmap? = null,
    val activeLayerId: String? = null,
    val activePanel: EditorPanel = EditorPanel.NONE,
    val editorMode: EditorMode = EditorMode.AR,
    // FIX: Default to NONE so transform gestures are always the baseline
    val activeTool: Tool = Tool.NONE,
    val hideUiForCapture: Boolean = false,
    val isRightHanded: Boolean = true,
    val gestureInProgress: Boolean = false,
    val showRotationAxisFeedback: Boolean = false,
    val activeRotationAxis: RotationAxis = RotationAxis.Z,
    val undoCount: Int = 0,
    val redoCount: Int = 0,
    val isLoading: Boolean = false,
    val brushSize: Float = 50f,
    // Feathering [0..1]: 0 = hard edge, 1 = fully soft (blur radius = brushSize)
    val brushFeathering: Float = 0f,
    val sketchThickness: Int = 5,
    val activeColor: Color = Color.White,
    val showColorPicker: Boolean = false,
    val showDiagOverlay: Boolean = false,

    // Real-time stroke rendering: the mutable bitmap being actively drawn into.
    // Non-null only while a brush stroke is in progress (non-Liquify tools).
    val liveStrokeLayerId: String? = null,
    val liveStrokeBitmap: Bitmap? = null,
    // Incremented after each stroke segment so Compose re-reads the modified pixels.
    val liveStrokeVersion: Int = 0,
    val canvasBackground: Color = Color.Black,
    val isSegmenting: Boolean = false,
    val segmentationInfluence: Float = 0.5f,
    val segmentationPreview: Bitmap? = null,
    val isStencilGenerating: Boolean = false,
    val stencilButtonPosition: Offset = Offset.Zero,
    val stencilHintVisible: Boolean = false,
)
